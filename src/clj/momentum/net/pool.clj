(ns momentum.net.pool
  (:refer-clojure
   :exclude [count drop])
  (:use
   momentum.utils.core)
  (:require
   [momentum.core.timer :as timer])
  (:import
   [java.util
    HashMap]))

(declare
 handle-keepalive-timeout
 reconnect)

(deftype Exchange [state pool addr connect-fn])
(defrecord ExchangeState [conn upstream])

(defprotocol IConnection
  (open?   [_])
  (close!  [_])
  (timeout [_ _])
  (next-global [_] [_ _])
  (prev-global [_] [_ _])
  (next-local  [_] [_ _])
  (prev-local  [_] [_ _]))

(defrecord ConnectionState
    [exchange
     exchange-count
     addrs
     dn])

(deftype Connection
    ;; Gotta use different names for the fields vs. the accessors with
    ;; mutable fields to get around a clojure bug.
    [state
     pool
     addr
     ^{:unsynchronized-mutable true} timeout
     ^{:unsynchronized-mutable true} open
     ^{:unsynchronized-mutable true} nxt-global
     ^{:unsynchronized-mutable true} prv-global
     ^{:unsynchronized-mutable true} nxt-local
     ^{:unsynchronized-mutable true} prv-local]

  IConnection
  (open? [this] (.open this))
  (close! [this] (set! open false))

  (timeout [this new-timeout]
    (locking this
      (when-let [existing (.timeout this)]
        (timer/cancel existing))

      (set! timeout new-timeout)))

  (next-global [this] (.nxt-global this))
  (next-global [this val] (set! nxt-global val))

  (prev-global [this] (.prv-global this))
  (prev-global [this val] (set! prv-global val))

  (next-local [this] (.nxt-local this))
  (next-local [this val] (set! nxt-local val))

  (prev-local [this] (.prv-local this))
  (prev-local [this val] (set! prv-local val)))

(defprotocol IPool
  (put   [this conn])
  (drop  [this conn])
  (poll  [this addr])
  (count [this addr])
  (clean [this conn])
  (purge [this]))

(deftype Pool
    [keepalive
     max-conns
     max-conns-per-addr
     lookup
     conns-for-addr
     ^{:unsynchronized-mutable true} conns
     ^{:unsynchronized-mutable true} head
     ^{:unsynchronized-mutable true} tail]

  IPool
  (put [this conn]
    (locking this
      (let [addr   (.addr conn)
            lookup (.lookup this)]
        ;; Update the global head
        (when (.head this)
          (prev-global (.head this) conn))

        (.next-global conn (.head this))
        (set! head conn)

        ;; Update the tail
        (when-not (.tail this)
          (set! tail conn))

        ;; Update the lookup map
        (when-let [local-head (.get lookup addr)]
          (prev-local local-head conn)
          (next-local conn local-head))

        (.put lookup addr conn)

        ;; Register the keepalive timeout
        (timeout
         conn
         (timer/register
          (.keepalive this)
          #(handle-keepalive-timeout this conn)))

        conn)))

  (drop [this conn]
    (locking this
      (timeout conn nil)

      (when (= (.head this) conn)
        (set! head (next-global conn)))

      (when (= (.tail this) conn)
        (set! tail (prev-global conn)))

      (when-let [next (next-global conn)]
        (prev-global next (prev-global conn)))

      (when-let [prev (prev-global conn)]
        (next-global prev (next-global conn)))

      (when-let [next (next-local conn)]
        (prev-local next (prev-local conn)))

      (when-let [prev (prev-local conn)]
        (next-local prev (next-local conn)))

      (let [addr   (.addr conn)
            lookup (.lookup this)]
        (when (= (.get lookup addr) conn)
          (if-let [next (next-local conn)]
            (do (prev-local next nil)
                (.put lookup addr next))
            (.remove lookup addr))))

      (next-global conn nil)
      (prev-global conn nil)
      (next-local conn nil)
      (prev-local conn nil)

      conn))

  (poll [this addr]
    (locking this
      (loop []
        (let [lookup (.lookup this)]
          (when-let [conn (.get lookup addr)]
            (drop this conn)
            conn)))))

  (count [this addr]
    (locking this
      (let [max-per-addr   (.max-conns-per-addr this)
            conns-for-addr (.get (.conns-for-addr this) addr)]

        (when (and conns-for-addr (>= conns-for-addr max-per-addr))
          (throw (Exception. (str "Reached maximum connections for: " addr))))

        (loop [max-conns (.max-conns this)]
          (when (>= (.conns this) max-conns)
            (if (purge this)
              (recur (.max-conns this))
              (throw (Exception. "Reached maximum total connections for the pool.")))))

        (set! conns (inc (.conns this)))
        (.put (.conns-for-addr this) addr (inc (or conns-for-addr 0)))

        true)))

  (clean [this conn]
    (locking this
      (when (.open? conn)
        (set! conns (dec (.conns this)))

        (let [addr       (.addr conn)
              addr-count (.get (.conns-for-addr this) addr)]
          (if (> addr-count 1)
            (.put (.conns-for-addr this) addr (dec addr-count))
            (.remove (.conns-for-addr this) addr)))

        (close! conn)

        (drop this conn))))

  (purge [this]
    (locking this
      (if-let [conn (.tail this)]
        (clean this conn)))))

(defn mk-connection
  ([pool addr] (mk-connection pool addr nil))
  ([pool addr exchange]
     (count pool addr)
     (Connection.
      (atom
       (ConnectionState.
        exchange ;; exchange
        0        ;; count
        nil      ;; addrs
        nil))    ;; dn
      pool       ;; pool
      addr       ;; addr
      nil        ;; timeout
      true       ;; open?
      nil        ;; nxt-global
      nil        ;; prv-global
      nil        ;; nxt-local
      nil)))     ;; prv-local

(defn mk-pool
  [{:keys [keepalive max-conns max-conns-per-addr]}]
  (Pool.
   (* 1000 (or keepalive 60)) ;; keepalive in ms
   (or max-conns 2000)          ;; max number of total connections
   (or max-conns-per-addr 200)  ;; max number of per address connections
   (HashMap.)                   ;; per address linked list head
   (HashMap.)                   ;; # of connections per addrress
   0                            ;; # of total connections
   nil                          ;; global linked list head
   nil))                        ;; global linked list tail

(defn- close-connection
  [conn]
  (let [current-state @(.state conn)]
    ((.dn current-state) :close nil)))

(defn- handle-keepalive-timeout
  [pool conn]
  (clean pool conn)
  (close-connection conn))

(defn- finalize-exchange
  [conn xchg]
  (swap-assoc! (.state conn) :exchange nil)
  (swap-assoc! (.state xchg) :conn nil))

(defn- mk-downstream
  [exchange]
  (fn [evt val]
    (let [exchange-state @(.state exchange)
          conn (.conn exchange-state)]

      (cond
       (= :reopen evt)
       (reconnect conn exchange)

       (not conn)
       (throw (Exception. "Not currently able to handle messages."))

       (and (= :close evt) (not val))
       (do
         (finalize-exchange conn exchange)
         (put (.pool conn) conn)
         ((.upstream exchange-state) :close nil))

       :else
       (let [next-dn (.dn @(.state conn))]
         (next-dn evt val))))))

(defn- maybe-bind-exchange
  [conn conn-state]
  (let [addrs    (.addrs    conn-state)
        exchange (.exchange conn-state)]
    ;; It is possible that the connection might already have been
    ;; closed. If that is the case, then the exchange will be set to nil.
    (when exchange
      (let [exchange-state @(.state exchange)]
        (when (and addrs (not (.conn exchange-state)))
          ;; Set the connection
          (swap! (.state exchange) #(assoc % :conn conn))
          ;; Track the binding
          (swap-then!
           (.state conn)
           (fn [conn-state]
             (let [cnt (inc (.exchange-count conn-state))]
               (assoc conn-state :exchange-count cnt)))
           (fn [conn-state]
             (let [upstream (.upstream exchange-state)
                   cnt (.exchange-count conn-state)]
               (upstream :open (assoc addrs :exchange-count cnt))))))))))

(defn mk-handler
  [conn]
  (fn [dn env]
    ;; Save off the downstream function. This function might change if
    ;; the upstream issues a :reopen event.
    (swap! (.state conn) #(assoc % :dn dn))

    (fn [evt val]
      (let [conn-state @(.state conn)
            xchg       (.exchange conn-state)
            xchg-state (when xchg @(.state xchg))
            upstream   (when xchg (.upstream xchg-state))]

        (cond
         (= :open evt)
         (maybe-bind-exchange conn (swap-assoc! (.state conn) :addrs val))

         (#{:close :abort} evt)
         (do
           ;; First, release the connection
           (clean (.pool conn) conn)

           ;; If an close or abort event is received before the
           ;; exchange has been bound, then a dud connection has been
           ;; checked out from the pool. The solution in this case is
           ;; restart the connect process with the current exchange.
           (when xchg
             (cond
              ;; Only :close events should be able to trigger a
              ;; reconnect
              (or (= :abort evt) (.conn xchg-state))
              (do
                (finalize-exchange conn xchg)
                (when upstream
                  (upstream evt val)))

              xchg
              (reconnect conn xchg))))

         :else
         (if upstream
           (upstream evt val)
           (throw (Exception. "Not in an exchange"))))))))

(defn- establish
  [exchange]
  (let [f    (.connect-fn exchange)
        conn (mk-connection (.pool exchange) (.addr exchange) exchange)]
    (f (mk-handler conn))
    conn))

(defn- checkout
  [exchange]
  (when-let [conn (poll (.pool exchange) (.addr exchange))]
    (swap-then!
     (.state conn)
     #(assoc % :exchange exchange)
     (fn [current-state]
       ((.dn current-state) :schedule
        #(maybe-bind-exchange conn @(.state conn)))))
    conn))

(defn- connect*
  [exchange]
  (try
    (or (checkout exchange)
        (establish exchange))
    (catch Exception err
      (let [current-state @(.state exchange)]
        ((.upstream current-state) :abort err)))))

(defn- reconnect
  [conn exchange]
  ;; First, unbind the exchange from the connection
  (swap-assoc! (.state exchange) :conn nil)

  (when conn
    (swap-then!
     (.state conn)
     #(assoc % :exchange nil)
     ;; Close the physical connection
     (fn [current-state]
       ((.dn current-state) :close nil))))

  ;; Obtain or establish a new connection
  (connect* exchange))

(defn- mk-exchange
  [app pool addr connect-fn]
  (let [state    (atom nil)
        exchange (Exchange. state pool addr connect-fn)
        upstream (app (mk-downstream exchange) {:connection-pool true})]
    (reset! state (ExchangeState. nil upstream))
    exchange))

(defn connect
  [pool app addr connect-fn]
  (connect* (mk-exchange app pool addr connect-fn)))
