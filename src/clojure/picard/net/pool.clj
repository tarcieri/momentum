(ns picard.net.pool
  (:use
   [picard.net.core :only [mk-socket-addr]])
  (:import
   [picard.net
    Connection
    ConnectionQueue]))

(defrecord Pool
    [queue
     connect-fn
     keepalive
     max-connections
     max-connections-per-address])

(defrecord ConnectionState
    [remote-addr
     local-addr
     open?
     upstream
     downstream]
  Connection
  (isOpen [this] (.open? this))
  (addr   [this] (.remote-addr this)))

(defn- mk-connection
  []
  (ConnectionState.
   nil      ;; remote-addr
   nil      ;; local-addr
   true     ;; open?
   nil      ;; upstream
   nil))    ;; downstream

;; (defn- mk-downstream-fn
;;   [state dn]
;;   (fn [evt val]
;;     (let [current-state @state]
;;       (when (.upstream current-state)
;;         (dn evt val)))))

;; (defn- bind
;;   [state dn]
;;   (let [current-state @state
;;         upstream ((.app current-state) (mk-downstream-fn state dn))]
;;     (swap!
;;      state
;;      (fn [current-state]
;;        (assoc current-state
;;          :upstream   upstream
;;          :downstream dn)))))

;; (defn- bind
;;   [state dn]
;;   (swap! state #(assoc % :downstream dn)))

(defn mk-handler
  [pool conn]
  (fn [dn]
    (fn [evt val]
      )))

(defn- establish-connection
  [pool addr]
  (let [conn (mk-connection)]
    ;; Stuff
    ))

(defn- checkout
  [pool addr]
  (.. pool queue (checkout addr)))

(defn connect
  [pool app {host :host port :port} connect-fn]
  (let [addr (mk-socket-addr [host port])]
    (or (checkout pool addr)
        )))

(def default-opts
  {:keepalive                   60
   :max-connections             1000
   :max-connections-per-address 200})

(defn- merge-default-opts
  [opts]
  (merge default-opts (if (map? opts) opts {})))

(defn mk-pool
  [connect-fn opts]
  (let [opts (merge-default-opts opts)]
    (Pool.
     (ConnectionQueue.)
     connect-fn
     (opts :keepalive)
     (opts :max-connections)
     (opts :max-connections-per-address))))
