(ns picard.net.pool
  (:use
   [picard.utils :only [swap-then!]])
  (:import
   [picard.net
    Connection
    ConnectionQueue
    StaleConnectionException]))

(defrecord Pool
    [queue
     keepalive
     max-connections
     max-connections-per-address])

(defmacro sync-get
  [conn field]
  `(locking ~conn
     (. ~conn ~field)))

(defmacro sync-set
  [conn & fields-and-vals]
  (when-not (= (mod (count fields-and-vals) 2) 0)
    (throw (IllegalArgumentException. "Need to pass an even number of fields / vals")))

  `(locking ~conn
     ~@(map
        (fn [[field val]] `(set! (. ~conn ~field) ~val))
        (partition 2 fields-and-vals))))

(defn- mk-connection
  [addr connect-fn]
  (Connection. addr connect-fn))

(defn- current-exchange?
  [conn dn]
  (locking conn
    (= (. conn exchangeDn) dn)))

(defn- finalize-exchange
  [conn]
  (locking conn
    (let [exchange-up (. conn exchangeUp)]
      (set! (. conn exchangeUp) nil)
      (set! (. conn exchangeDn) nil)
      exchange-up)))

(defn- mk-downstream
  [pool conn next-dn]
  (fn current [evt val]
    (cond
     (not (current-exchange? conn current))
     (throw (Exception. "Current exchange is complete"))

     (= [:close nil] [evt val])
     (let [exchange-up (finalize-exchange conn)]
       (exchange-up :close nil)
       (.. pool queue (checkin conn)))

     :else
     (next-dn evt val))))

(defn- abort-app
  [app err]
  (let [upstream (app (fn [_ _]))]
    (upstream :abort err)))

;; Ideally, if the connection is closed, it should retry
(defn- maybe-bind-app
  [pool conn app]
  (when-not (sync-get conn exchangeUp)
    (when-let [addrs (sync-get conn addrs)]
      (if (sync-get conn isOpen)
        (let [next-dn     (. conn downstream)
              exchange-dn (mk-downstream pool conn next-dn)
              exchange-up (app exchange-dn)]
          (sync-set conn exchangeDn exchange-dn exchangeUp exchange-up)
          (exchange-up :open (assoc addrs :exchange-count (. conn inc))))
        ;; TODO: Handle when the connection is closed.
        (abort-app app (StaleConnectionException.
                        "The connection has been closed"))))))

(defn mk-handler
  [pool conn app]
  (fn [dn]
    (locking conn (set! (. conn downstream) dn))
    (fn [evt val]
      ;; (println "(P) UP: " [evt val])
      (cond
       (= :message evt)
       (when-let [exchange-up (locking conn (. conn exchangeUp))]
         (exchange-up :message val))

       (= :open evt)
       (do
         (locking conn (set! (. conn addrs) val))
         (maybe-bind-app pool conn app))

       (= :close evt)
       (do
         (sync-set conn isOpen false)
         (if-let [exchange-up (finalize-exchange conn)]
           (exchange-up :close val)
           (.. pool queue (remove conn))))

       ;; If the connection has not been bound, bind it here.
       (= :abort evt)
       (let [exchange-up (finalize-exchange conn)]
         (cond
          exchange-up
          (exchange-up :abort val)

          (nil? (locking conn (. conn addrs)))
          (abort-app app val)))))))

(defn- schedule-bind-app
  [pool conn app]
  (let [dn (sync-get conn downstream)]
    (dn :schedule #(maybe-bind-app pool conn app))))

(defn- create
  [pool addr app connect-fn]
  (let [conn (mk-connection addr connect-fn)]
    (connect-fn (mk-handler pool conn app))
    conn))

(defn- get-connection
  [pool addr app connect-fn]
  (or (.. pool queue (checkout addr))
      (create pool addr app connect-fn)))

(defn connect
  [pool app addr connect-fn]
  (let [conn (get-connection pool addr app connect-fn)]
    (schedule-bind-app pool conn app)))

(def default-opts
  {:keepalive                   60
   :max-connections             1000
   :max-connections-per-address 200})

(defn- merge-default-opts
  [opts]
  (merge default-opts (if (map? opts) opts {})))

(defn mk-pool
  [opts]
  (let [opts (merge-default-opts opts)]
    (Pool.
     (ConnectionQueue.)
     (opts :keepalive)
     (opts :max-connections)
     (opts :max-connections-per-address))))
