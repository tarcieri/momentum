(ns picard.http.websocket
  (:use
   picard.utils.buffer
   picard.utils.core)
  (:require
   [picard.utils.base64 :as base64]
   [picard.utils.digest :as digest])
  (:import
   [picard.http
    WsFrame
    WsFrameEncoder
    WsFrameType]))

(defrecord Socket
    [state
     version
     extensions])

(def opcodes
  {:continuation WsFrameType/CONTINUATION
   :text         WsFrameType/TEXT
   :binary       WsFrameType/BINARY
   :close        WsFrameType/CLOSE
   :ping         WsFrameType/PING
   :pong         WsFrameType/PONG})

(defn- mk-socket
  []
  (atom (Socket. :CONNECTING nil nil)))

(defn- frame
  [type data]
  (.. (WsFrameEncoder. (WsFrame. (opcodes type)))
      (encode (buffer data))))

(defn- abort-socket
  [state dn]
  (dn :close nil))

(defn- accept-socket
  [state dn key]
  (let [sig  (digest/sha1 (str key "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
        hdrs {"connection"   "upgrade"
              "upgrade"      "websocket"
              "content-type" "utf-8"
              "sec-websocket-accept" (base64/encode sig)}]

    (swap! state #(assoc % :state :OPEN))
    (println "SENDING HANDSHAKE")

    (dn :response [101 hdrs ""])
    (dn :message (frame :text "HELLO"))))

(defn- handshake
  [state dn [{upgrade    "upgrade"
              connection "connection"
              version    "sec-websocket-version"
              key        "sec-websocket-key"
              origin     "sec-websocket-origin"
              protos     "sec-websocket-protocol"
              exts       "sec-websocket-extensions"} body]]

  ;; If the request is a websocket request, initiate the handshake.
  (when (and (= :upgraded body) (= upgrade "websocket"))
    (if (and key origin)
      (accept-socket state dn key)
      (abort-socket state dn))
    true))

(defn proto
  [app]
  (fn [dn]
    (println "ZOOOOOOOOOMG")
    (let [up (app dn)
          state (mk-socket)]
      (defstream
        (request [request]
          (println "UP: " [:request request])
          (when-not (handshake state dn request)
            (println "NO HANDSHAKE")
            (up :request request)))

        (message [buf]
          (println "UP: " [:message buf]))

        (abort [err]
          (.printStackTrace err))

        (else [evt val]
          (println "UP: " [evt val])
          (up evt val))))))
