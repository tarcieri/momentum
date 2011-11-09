(ns momentum.http.websocket
  (:use
   momentum.core.buffer
   momentum.utils.core)
  (:require
   [momentum.utils.base64 :as base64]
   [momentum.utils.digest :as digest])
  (:import
   [momentum.http
    WsFrame
    WsFrameDecoder
    WsFrameType]))

;; TODO:
;; * Timeouts (around closing frames and such things)

(defrecord Socket
    [state
     version
     extensions
     key])

(def opcodes
  {:continuation WsFrameType/CONTINUATION
   :text         WsFrameType/TEXT
   :binary       WsFrameType/BINARY
   :close        WsFrameType/CLOSE
   :ping         WsFrameType/PING
   :pong         WsFrameType/PONG})

(def status-codes
  {:normal           1000
   :going-away       1001
   :proto-error      1002
   :unacceptable     1003
   :frame-too-large  1004
   :invalid-encoding 1007})

(def status-tokens
  (into {} (map (comp vec reverse) status-codes)))

(def salt "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")

(defn- mk-socket
  []
  (atom
   (Socket.
    :pending
    nil
    nil
    nil)))

(defn- response-key
  [current-state]
  (base64/encode
   (digest/sha1
    (str (.key current-state) salt))))

(defn- frame
  [data]
  (.encode
   (cond
    (buffer? data)
    (WsFrame. WsFrameType/BINARY data)

    :else
    (WsFrame. WsFrameType/TEXT (buffer (str data))))))

(defn- close-frame
  [status msg]
  (let [frame (WsFrame. WsFrameType/CLOSE (buffer (str msg)))]
    (.statusCode frame (status-codes status 0))
    (.encode frame)))

(defn- abort-socket
  [state dn]
  (swap-assoc! state :state :closed)
  (dn :close (status-codes :proto-error)))

(defn- accept-socket
  [state dn hdrs]
  (swap-then!
   state
   ;; Atomically ensure that the transition is from connecting to open
   (fn [current-state]
     (if (= :connecting (.state current-state))
       (assoc current-state :state :open)
       current-state))
   ;; Then, send the event downstream
   (fn [current-state]
     (if (not= :open (.state current-state))
       ;; The state changed from underneath us
       (abort-socket state dn)
       ;; Send the handshake
       (dn :response [101 (merge {"connection" "upgrade"
                                  "upgrade"    "websocket"
                                  "sec-websocket-accept"
                                  (response-key current-state)} hdrs) :upgraded])))))

(defn- request-upgrade
  [state up key request]
  ;; Save off the exchange key and send the request upstream
  (swap-assoc! state :state :connecting :key key)
  (up :request request))

(defn- receive-response
  [state dn response current-state]
  (if (= :passthrough (.state current-state))
    ;; If the connection is currently a passthrough connection, just
    ;; send the response downstream as is.
    (dn :response response)

    ;; Otherwise, process it as a websocket handshake response
    (let [[status hdrs body] response]
      (if (= 101 status)
        (accept-socket state dn hdrs)
        (do
          (swap-assoc! state :state :passthrough)
          (dn :response response))))))

(defn- receive-request
  [state up dn [{upgrade    "upgrade"
                 connection "connection"
                 version    "sec-websocket-version"
                 key        "sec-websocket-key"
                 origin     "sec-websocket-origin"
                 protos     "sec-websocket-protocol"
                 exts       "sec-websocket-extensions"} body :as request]]

  (if (and (= :upgraded body) (= upgrade "websocket"))
    ;; If the request is a websocket request, initiate the handshake.
    (if key
      (request-upgrade state up key request)
      (abort-socket state dn))
    ;; Otherwise, mark the socket as pass through and send the request
    ;; upstream
    (do
      (swap-assoc! state :state :passthrough)
      (up :request request))))

(defn- handle-frame
  [state up dn frame]
  (cond
   (.isText frame)
   (up :message (.text frame))

   (.isClose frame)
   (let [status (.statusCode frame)]
     (swap-then!
      state
      (fn [conn]
        (assoc conn :state ({:open :closing} (.state conn) :closed)))
      (fn [conn]
        ;; When the state currently is closing, then the client has
        ;; sent a close frame. We notify the upstream that the socket
        ;; is now closed, respond with a close frame, then close the socket.
        (when (= :closing (.state conn))
          (up :close (status-tokens status))
          (dn :message (close-frame :normal "Replying to close frame")))
        (dn :close nil))))

   :else
   (throw (Exception. "Not implemented yet"))))

(defn- mk-decoder
  [state up dn]
  (let [decoder (WsFrameDecoder. true #(handle-frame state up dn %))]
    (fn [buf]
      (.decode decoder buf))))

(defn- mk-downstream
  [state dn]
  (fn [evt val]
    (let [current-state @state]
      (cond
       (= :response evt)
       (receive-response state dn val current-state)

       (= :message evt)
       (let [sock-state (.state current-state)]
         (cond
          (= :open sock-state)
          (dn :message (frame val))

          (= :passthrough sock-state)
          (dn :message val)

          :else
          (throw (Exception. "Not currently expecting message"))))

       (= :close evt)
       (let [sock-state (.state current-state)]
         (cond
          (= :passthrough sock-state)
          (dn :close val)

          :else
          (swap-then!
           state
           (fn [conn]
             (let [currently (.state conn)]
               (assoc conn :state ({:open :closing} currently :closed))))
           (fn [conn]
             ;; If the socket state is closing, then the upstream has
             ;; initiated the closing handshake, so we must send a
             ;; close frame to the client.
             (when (= :closing (.state conn))
               (dn :message (close-frame :normal "")))))))

       :else
       (dn evt val)))))

(defn proto
  [app]
  (fn [dn env]
    (let [state  (mk-socket)
          up     (app (mk-downstream state dn) env)
          decode (mk-decoder state up dn)]

      (fn [evt val]
        (let [current-state @state]
          (cond
           (= :message evt)
           (decode val)

           (= :request evt)
           (receive-request state up dn val)

           (= :body evt)
           (if (= :passthrough (.state current-state))
             (up :body val)
             (throw (Exception. "Not currently expecting a body event")))

           (= :abort evt)
           (do
             (swap! state #(assoc % :state :closed))
             (up :abort val))

           :else
           (up evt val)))))))
