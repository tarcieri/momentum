(ns picard.net.pool
  (:use
   [picard.net.core :only [mk-socket-addr]])
  (:import
   [picard.net
    Connection
    ConnectionPool]))

(defrecord PooledConnection
    [remote-addr
     local-addr
     open?
     upstream
     downstream
     connect-fn]
  Connection
  (isOpen [this] (.open? this))
  (addr   [this] (.remote-addr this)))

(defn- initial-state
  []
  (PooledConnection.
   nil      ;; remote-addr
   nil      ;; local-addr
   true     ;; open?
   nil      ;; upstream
   nil      ;; downstream
   nil))    ;; connect-fn

;; (defn mk-state
;;   []
;;   (atom (initial-state)))

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

;; (defn mk-handler
;;   [state]
;;   (fn [dn]
;;     (bind state dn)
;;     (fn [evt val]
;;       (let [current-state @state]
;;         ((.upstream current-state) evt val)))))

(defn- checkout
  [addr])

(defn connect
  [pool app {host :host port :port} connect-fn]
  (let [addr (mk-socket-addr [host port])]))

(defn mk-pool
  [opts]
  opts)
