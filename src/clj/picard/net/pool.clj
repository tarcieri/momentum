(ns picard.net.pool
  (:refer-clojure
   :exclude [remove drop])
  (:use
   [picard.utils :only [swap-then!]])
  ;; (:import
  ;;  [picard.net
  ;;   Connection
  ;;   ConnectionQueue
  ;;   StaleConnectionException])
  )

(declare
 open?)

(defprotocol IConnection
  (next-global [_] [_ _])
  (prev-global [_] [_ _])
  (next-local  [_] [_ _])
  (prev-local  [_] [_ _]))

(defrecord ConnectionState
    [addrs
     dn
     open?
     exchange-up
     exchange-dn
     exchange-cnt])

(deftype Connection
    [state
     addr
     ;; Gotta use different names for the fields vs. the accessors to
     ;; get around a clojure bug.
     ^{:unsynchronized-mutable true} nxt-global
     ^{:unsynchronized-mutable true} prv-global
     ^{:unsynchronized-mutable true} nxt-local
     ^{:unsynchronized-mutable true} prv-local]

  IConnection
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
  (purge [this]))

(deftype Pool
    [keepalive
     max-conns
     max-conns-per-addr
     lookup
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
        (when-let [local-head (lookup addr)]
          (prev-local local-head conn)
          (next-local conn local-head))

        (assoc! lookup addr conn)
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
        (when (= (lookup addr) conn)
          (if-let [next (next-local conn)]
            (do (prev-local next nil)
                (assoc! lookup addr next))
            (dissoc! lookup addr))))

      (next-global conn nil)
      (prev-global conn nil)
      (next-local conn nil)
      (prev-local conn nil)

      conn))

  (poll [this addr]
    (locking this
      (loop []
        (let [lookup (.lookup this)]
          (when-let [conn (lookup addr)]
            (drop this conn)
            (if-not (open? conn)
              (recur)
              conn))))))

  (purge [this]
    (locking this
      (if-let [conn (.tail this)]
        (drop this conn)))))

(defn- open?
  [^Connection conn]
  (let [state (.state conn)]
    (.open? @state)))

(defn close!
  [^Connection conn]
  (swap! (.state conn) #(assoc % :open? false)))

(defn mk-connection
  [addr]
  (Connection.
   (atom
    (ConnectionState.
     nil   ;; addrs
     nil   ;; dn
     true  ;; open?
     nil   ;; exchange-up
     nil   ;; exchange-dn
     0))   ;; exchange-cnt
   addr    ;; addr
   nil     ;; nxt-global
   nil     ;; prv-global
   nil     ;; nxt-local
   nil))   ;; prv-local

(defn mk-pool
  [{:keys [keepalive max-conns max-conns-per-addr]}]
  (Pool.
   (or keepalive 60)
   (or max-conns 2000)
   (or max-conns-per-addr 200)
   (transient {})
   nil
   nil))



;; (defmacro sync-get
;;   [conn field]
;;   `(locking ~conn
;;      (. ~conn ~field)))

;; (defmacro sync-set
;;   [conn & fields-and-vals]
;;   (when-not (= (mod (count fields-and-vals) 2) 0)
;;     (throw (IllegalArgumentException. "Need to pass an even number of fields / vals")))

;;   `(locking ~conn
;;      ~@(map
;;         (fn [[field val]] `(set! (. ~conn ~field) ~val))
;;         (partition 2 fields-and-vals))))

;; (defn- mk-connection
;;   [addr connect-fn]
;;   (Connection. addr connect-fn))

;; (defn- current-exchange?
;;   [conn dn]
;;   (locking conn
;;     (= (. conn exchangeDn) dn)))

;; (defn- finalize-exchange
;;   [conn]
;;   (locking conn
;;     (let [exchange-up (. conn exchangeUp)]
;;       (set! (. conn exchangeUp) nil)
;;       (set! (. conn exchangeDn) nil)
;;       exchange-up)))

;; (defn- mk-downstream
;;   [pool conn next-dn]
;;   (fn current [evt val]
;;     (cond
;;      (not (current-exchange? conn current))
;;      (throw (Exception. "Current exchange is complete"))

;;      (= [:close nil] [evt val])
;;      (let [exchange-up (finalize-exchange conn)]
;;        (exchange-up :close nil)
;;        (.. pool queue (checkin conn)))

;;      :else
;;      (next-dn evt val))))

;; (defn- abort-app
;;   [app err]
;;   (let [upstream (app (fn [_ _]))]
;;     (upstream :abort err)))

;; ;; Ideally, if the connection is closed, it should retry
;; (defn- maybe-bind-app
;;   [pool conn app]
;;   (when-not (sync-get conn exchangeUp)
;;     (when-let [addrs (sync-get conn addrs)]
;;       (if (sync-get conn isOpen)
;;         (let [next-dn     (. conn downstream)
;;               exchange-dn (mk-downstream pool conn next-dn)
;;               exchange-up (app exchange-dn)]
;;           (sync-set conn exchangeDn exchange-dn exchangeUp exchange-up)
;;           (exchange-up :open (assoc addrs :exchange-count (. conn inc))))
;;         ;; TODO: Handle when the connection is closed.
;;         (abort-app app (StaleConnectionException.
;;                         "The connection has been closed"))))))

;; (defn mk-handler
;;   [pool conn app]
;;   (fn [dn]
;;     (locking conn (set! (. conn downstream) dn))
;;     (fn [evt val]
;;       ;; (println "(P) UP: " [evt val])
;;       (cond
;;        (= :message evt)
;;        (when-let [exchange-up (locking conn (. conn exchangeUp))]
;;          (exchange-up :message val))

;;        (= :open evt)
;;        (do
;;          (locking conn (set! (. conn addrs) val))
;;          (maybe-bind-app pool conn app))

;;        (= :close evt)
;;        (do
;;          (sync-set conn isOpen false)
;;          (if-let [exchange-up (finalize-exchange conn)]
;;            (exchange-up :close val)
;;            (.. pool queue (remove conn))))

;;        ;; If the connection has not been bound, bind it here.
;;        (= :abort evt)
;;        (let [exchange-up (finalize-exchange conn)]
;;          (cond
;;           exchange-up
;;           (exchange-up :abort val)

;;           (nil? (locking conn (. conn addrs)))
;;           (abort-app app val)))))))

;; (defn- schedule-bind-app
;;   [pool conn app]
;;   (let [dn (sync-get conn downstream)]
;;     (dn :schedule #(maybe-bind-app pool conn app))))

;; (defn- create
;;   [pool addr app connect-fn]
;;   (let [conn (mk-connection addr connect-fn)]
;;     (connect-fn (mk-handler pool conn app))
;;     conn))

;; (defn- get-connection
;;   [pool addr app connect-fn]
;;   (or (.. pool queue (checkout addr))
;;       (create pool addr app connect-fn)))

;; (defn connect
;;   [pool app addr connect-fn]
;;   (let [conn (get-connection pool addr app connect-fn)]
;;     (schedule-bind-app pool conn app)))

;; (def default-opts
;;   {:keepalive                   60
;;    :max-connections             1000
;;    :max-connections-per-address 200})

;; (defn- merge-default-opts
;;   [opts]
;;   (merge default-opts (if (map? opts) opts {})))

;; (defn mk-pool
;;   [opts]
;;   (let [opts (merge-default-opts opts)]
;;     (Pool.
;;      (ConnectionQueue.)
;;      (opts :keepalive)
;;      (opts :max-connections)
;;      (opts :max-connections-per-address))))

(defn connect
  [& args])
