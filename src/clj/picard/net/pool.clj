(ns picard.net.pool
  (:refer-clojure
   :exclude [count drop])
  (:use
   [picard.utils :only [swap-then!]])
  (:import
   [java.util
    HashMap]))

(declare
 maybe-bind-app)

(defprotocol IConnection
  (open?  [_])
  (close! [_])
  (next-global [_] [_ _])
  (prev-global [_] [_ _])
  (next-local  [_] [_ _])
  (prev-local  [_] [_ _]))

(defrecord ConnectionState
    [addrs
     dn
     exchange-up
     exchange-dn
     exchange-cnt])

(deftype Connection
    [state
     pool
     addr
     connect-fn
     ^{:unsynchronized-mutable true} open
     ;; Gotta use different names for the fields vs. the accessors to
     ;; get around a clojure bug.
     ^{:unsynchronized-mutable true} nxt-global
     ^{:unsynchronized-mutable true} prv-global
     ^{:unsynchronized-mutable true} nxt-local
     ^{:unsynchronized-mutable true} prv-local]

  IConnection
  (open? [this] (.open this))
  (close! [this] (set! open false))

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
        conn)))

  (drop [this conn]
    (locking this
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
  ([pool addr connect-fn]
     (count pool addr)
     (Connection.
      (atom
       (ConnectionState.
        nil      ;; addrs
        nil      ;; dn
        nil      ;; exchange-up
        nil      ;; exchange-dn
        0))      ;; exchange-cnt
      pool       ;; pool
      addr       ;; addr
      connect-fn ;; connect-fn
      true       ;; open?
      nil        ;; nxt-global
      nil        ;; prv-global
      nil        ;; nxt-local
      nil)))     ;; prv-local

(defn mk-pool
  [{:keys [keepalive max-conns max-conns-per-addr] :as opts}]
  (Pool.
   (or keepalive 60)
   (or max-conns 2000)
   (or max-conns-per-addr 200)
   (HashMap.)
   (HashMap.)  ;; conns-per-addr
   0           ;; conns
   nil
   nil))

(defn- mk-downstream
  [conn next-dn]
  (fn current [evt val]
    (let [current-state @(.state conn)]
      (cond
       (not= current (.exchange-dn current-state))
       (throw (Exception. "Current exchange is complete"))

       (= [:close nil] [evt val])
       (let [exchange-up (.exchange-up current-state)]
         (swap!
          (.state conn)
          #(assoc % :exchange-up nil :exchange-dn nil))
         (exchange-up :close nil)
         (put (.pool conn) conn))


       :else
       (next-dn evt val)))))

(defn- abort-app
  [app err]
  (let [upstream (app (fn [_ _]))]
    (upstream :abort err)))

(defn mk-handler
  [conn app]
  (fn [dn]
    (swap! (.state conn) #(assoc % :dn dn))
    (fn [evt val]
      (let [current-state @(.state conn)]
        (cond
         (= :message evt)
         (when-let [exchange-up (.exchange-up current-state)]
           (exchange-up :message val))

         (= :open evt)
         (swap-then!
          (.state conn)
          #(assoc % :addrs val)
          (fn [current-state]
            (maybe-bind-app conn current-state app)))

         (#{:close :abort} evt)
         (do
           (clean (.pool conn) conn)
           (swap!
            (.state conn)
            #(assoc %
               :exchange-up nil
               :exchange-dn nil))
           (if-let [exchange-up (.exchange-up current-state)]
             (exchange-up evt val)
             (when (and (= :abort evt) (not (.addrs current-state)))
               (abort-app app val)))))))))

(defn- maybe-bind-app
  [conn current-state app]
  (when-not (.exchange-up current-state)
    (when-let [addrs (.addrs current-state)]
      (let [next-dn     (.dn current-state)
            exchange-dn (mk-downstream conn next-dn)
            exchange-up (app exchange-dn)]
        (swap-then!
         (.state conn)
         (fn [current-state]
           (assoc current-state
             :exchange-dn exchange-dn
             :exchange-up exchange-up
             :exchange-cnt (inc (.exchange-cnt current-state))))
         (fn [current-state]
           (let [count (.exchange-cnt current-state)]
             (exchange-up :open (assoc addrs :exchange-count count)))))))))

(defn- establish
  [pool addr app connect-fn]
  (let [conn (mk-connection pool addr connect-fn)]
    (connect-fn (mk-handler conn app))
    conn))

(defn- connection
  [pool addr app connect-fn]
  (or (poll pool addr)
      (establish pool addr app connect-fn)))

(defn connect
  [pool app addr connect-fn]
  (try
    (let [conn (connection pool addr app connect-fn)
          current-state @(.state conn)
          downstream    (.dn current-state)]
      (downstream :schedule #(maybe-bind-app conn @(.state conn) app))
      conn)
    (catch Exception err
      (abort-app app err))))
