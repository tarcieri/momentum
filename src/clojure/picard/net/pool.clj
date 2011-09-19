(ns picard.net.pool
  (:use
   [picard.utils :only [swap-then!]])
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
     downstream
     exchange-up
     exchange-dn]
  Connection
  (isOpen [this] (.open? this))
  (addr   [this] (.remote-addr this)))

(defn- mk-connection
  [connect-fn]
  (ConnectionState.
   connect-fn ;; connect-fn
   nil        ;; addrs
   true       ;; open?
   nil        ;; downstream
   nil        ;; exchange-up
   nil))      ;; exchange-dn

(defn- mk-downstream
  [state next-dn]
  (fn current [evt val]
    ))

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
  [state app]
  (let [current-state @state
        next-dn     (.downstream current-state)
        exchange-dn (mk-downstream state next-dn)
        exchange-up (app exchange-dn)]
    (swap-then!
     state
     #(assoc %
        :exchange-up exchange-up
        :exchange-dn exchange-dn)
     (fn [current-state]
       (when-let [addrs (.addrs current-state)]
         ;; Unfortunetly, this event is dispatched on an unrelated
         ;; thread to the stream which could cause some threading issues.
         (exchange-up :open addrs))))))

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
    (bind-app state app)))

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
