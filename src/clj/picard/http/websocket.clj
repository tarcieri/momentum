(ns picard.http.websocket
  (:use
   picard.core.buffer
   picard.utils.core)
  (:require
   [picard.utils.base64 :as base64]
   [picard.utils.digest :as digest])
  (:import
   [picard.http
    WsFrame
    WsFrameDecoder
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

(defn- accept-key
  [key]
  (base64/encode
   (digest/sha1
    (str key "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))))

(defn- frame
  [type data]
  (.encode (WsFrame. (opcodes type) data)))

(defn- abort-socket
  [state dn]
  (dn :close nil))

(defn- accept-socket
  [state dn key]
  (let [hdrs {"connection"           "upgrade"
              "upgrade"              "websocket"
              "content-type"         "utf-8"
              "sec-websocket-accept" (accept-key key)}]

    (swap! state #(assoc % :state :OPEN))

    (dn :response [101 hdrs])
    (dn :message (frame :text (buffer "HELLO")))))

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

(defn- handle-frame
  [frame]
  (println frame ": " (.text frame)))

(defn- mk-decoder
  [up]
  (let [decoder (WsFrameDecoder. true handle-frame)]
    (fn [buf]
      (.decode decoder buf))))

(defn proto
  [app]
  (fn [dn]
    (let [up     (app dn)
          state  (mk-socket)
          decode (mk-decoder up)]

      (defstream
        (request [request]
          (when-not (handshake state dn request)
            (up :request request)))

        (message [buf]
          (decode buf))

        (abort [err]
          (.printStackTrace err))

        (else [evt val]
          (up evt val))))))
