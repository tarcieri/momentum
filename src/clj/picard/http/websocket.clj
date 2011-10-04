(ns picard.http.websocket
  (:require
   [picard.utils.digest :as digest]
   [picard.utils.base64 :as b64]))

;; STATES:
;; * CONNECTING
;; * ABORTED

(defrecord Socket
    [state
     version
     extensions])

(defn- mk-socket
  []
  (atom (Socket. :CONNECTING nil nil)))

(defn- abort-socket
  [state dn]
  (println "ABORTING!!!")
  (dn :close nil))

(defn- accept-socket
  [state dn key]
  (let [sig  (str key "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
        hdrs {"connection" "upgrade"
              "upgrade"    "websocket"
              "sec-websocket-accept" sig}]
    (println "ACCEPTING")
    (dn :response [101 hdrs ""])))

(defn- handshake
  [state dn [{upgrade    "upgrade"
              connection "connection"
              version    "sec-websocket-version"
              key        "sec-websocket-key"
              origin     "sec-websocket-origin"
              protos     "sec-websocket-protocol"
              exts       "sec-websocket-extensions"} :as request]]

  ;; If the request is a websocket request, initiate the handshake.
  (when (= upgrade "websocket")
    ;; The state will starts at CONNECTING
    (reset! state :CONNECTING)

    (if (and key origin)
      (accept-socket state dn key)
      (abort-socket state dn))))

(defn proto
  [app]
  (fn [dn]
    (let [up (app dn)
          state (atom nil)]
      (fn [evt val]
        (cond
         (= :request evt)
         (when-not (handshake state dn val)
           (up evt val))

         :else
         (up evt val))))))
