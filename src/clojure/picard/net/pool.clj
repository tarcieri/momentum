(ns picard.net.pool
  (:import
   [picard.net
    Connection
    ConnectionQueue]))

(defrecord Pool
    [queue
     keepalive
     max-connections
     max-connections-per-address])

(defrecord ConnectionState
    [connect-fn
     addrs
     open?
     upstream
     downstream]
  Connection
  (isOpen [this] (.open? this))
  (addr   [this] (.remote-addr this)))

(defn- mk-connection
  [connect-fn]
  (ConnectionState.
   connect-fn ;; connect-fn
   nil        ;; addrs
   true       ;; open?
   nil        ;; upstream
   nil))      ;; downstream

(defn- mk-downstream
  [state])

(defn mk-handler
  [pool state]
  (fn [dn]
    (swap! state #(assoc % :downstream dn))
    (fn [evt val]
      (cond
       (= :open evt)
       (swap! state #(assoc % :addrs val))

       (= :close evt)
       (swap! state #(assoc % :open? false))

       (= :abort evt)
       1
       ))))

(defn- bind-app
  [current-state app]
  (let [dn (.downstream current-state)]
    ))

(defn- create
  [pool addr connect-fn]
  (let [state (atom (mk-connection connect-fn))]
    (connect-fn (mk-handler pool state))
    state))

(defn- get-connection
  [pool addr connect-fn]
  (or (.. pool queue (checkout addr))
      (create pool addr connect-fn)))

(defn connect
  [pool app addr connect-fn]
  (let [state (get-connection pool addr connect-fn)]
    (bind-app @state app)))

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
