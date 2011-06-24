(ns picard.server
  (:use
   [picard.utils :rename {debug debug*}])
  (:require
   [clojure.string :as str]
   [picard.netty :as netty])
  (:import
   [org.jboss.netty.buffer
    ChannelBuffer]
   [org.jboss.netty.channel
    Channel
    ChannelState]
   [org.jboss.netty.handler.codec.http
    DefaultHttpChunk
    DefaultHttpResponse
    HttpChunk
    HttpHeaders
    HttpMessage
    HttpMethod
    HttpRequest
    HttpRequestDecoder
    HttpResponse
    HttpResponseEncoder
    HttpResponseStatus
    HttpVersion]
   [java.io
    IOException]))

;; TODO:
;; * content-length header != body size???
;; * track request body content-length? Netty does this?

(defrecord State
    [app
     ch
     keepalive?
     chunked?
     streaming? ;; TODO: remove this
     responded?
     expects-100?
     request-id
     next-up-fn
     next-dn-fn
     upstream
     bytes-expected
     bytes-to-send
     last-write
     options
     aborting?
     timeout])

;; Declare some functions in advance
(declare
 incoming-request
 stream-request-body
 awaiting-100-continue
 waiting-for-response
 handle-err)

(def global-timer netty/global-timer)

(defmacro debug
  [& msgs]
  `(debug* :server ~@msgs))

(defn- mk-initial-state
  ([app options] (mk-initial-state app options nil))
  ([app options ch]
     (State. app               ;; Application for the server
             ch                ;; Netty channel
             true              ;; Is the exchange keepalive?
             nil               ;; Is the request chunked?
             nil               ;; Is the response streaming?
             nil               ;; Has the response been sent?
             false             ;; Does the exchange expect an 100 Continue?
             nil               ;; Request ID
             incoming-request  ;; Next upstream event handler
             nil               ;; Next downstream event handler
             nil               ;; Upstream handler (external interface)
             nil               ;; Bytes expected
             0                 ;; Bytes sent
             nil               ;; Last write to the channel
             options           ;; Server options
             false             ;; Are we aborting the exchange?
             nil)))            ;; Timeout reference

(defn- content-length
  [hdrs]
  (when-let [content-length (hdrs "content-length")]
    (if (number? content-length)
      (long content-length)
      (Long. (str content-length)))))

(defn- chunk-size
  [chunk]
  (cond
   (or (= :chunked chunk) (nil? chunk))
   0

   (instance? ChannelBuffer chunk)
   (.readableBytes ^ChannelBuffer chunk)

   :else
   (count chunk)))

(defn- exchange-finished?
  [current-state]
  (and (.responded? current-state)
       (or (= waiting-for-response (.next-up-fn current-state))
           (= awaiting-100-continue (.next-up-fn current-state)))))

(defn- fresh-state
  [state]
  (mk-initial-state (.app state) (.options state) (.ch state)))

(defn- clear-timeout
  [state current-state]
  (when-let [old-timeout (.timeout current-state)]
    (netty/cancel-timeout old-timeout)))

(defn- bump-timeout
  ([state current-state msg]
     (bump-timeout
      state
      (* (:timeout (.options current-state)) 1000)
      current-state msg))
  ([state ms current-state msg]
     (clear-timeout state current-state)
     (let [msg (if (= :exchange msg)
                 "HTTP exchange timed out"
                 "HTTP keep alive timed out")
           new-timeout
           (netty/on-timeout
            global-timer ms
            #(handle-err state (Exception. msg) @state))]
       (swap! state #(assoc % :timeout new-timeout)))))

(defn- start-keepalive-timer
  [state current-state]
  (bump-timeout
   state (* (:keepalive (.options current-state)) 1000)
   current-state :keepalive))

(defn- write-msg
  [state current-state msg]
  (let [channel    ^Channel (.ch current-state)
        last-write (.write channel msg)]
    (swap! state #(assoc % :last-write last-write))
    last-write))

(defn- write-last-msg
  [state current-state msg close-channel?]
  (let [last-write (if msg
                     (write-msg state current-state msg)
                     (.last-write current-state))]
    (when close-channel?
      (.addListener last-write netty/close-channel-future-listener))))

(defn- finalize-exchange
  [state current-state last-msg]
  (let [upstream (.upstream current-state)]
    (if (exchange-finished? current-state)
      ;; The HTTP exchange is finished, send up an event to let all
      ;; middleware / applications cleanup after themselves.
      (do
        (debug {:msg "Sending upstream" :event [:done nil] :state current-state})
        (upstream :done nil)

        ;; Either the connection will be closed or a new timer will be
        ;; created. Either way, we don't want the existing activity
        ;; timer to trigger
        (clear-timeout state current-state)

        (if (.keepalive? current-state)
          (let [new-state (fresh-state current-state)]
            (reset! state new-state)
            (debug {:msg "Keeping connection alive" :state current-state})
            (start-keepalive-timer state current-state)
            (write-last-msg state current-state last-msg false))
          (do
            (swap! state #(assoc % :upstream nil))
            (debug {:msg "Killing connection" :state current-state})
            (write-last-msg state current-state last-msg true))))
      (write-last-msg state current-state last-msg false))))

(defn- aborted-req
  [_ evt val _]
  (throw
   (Exception.
    (str "This request has been aborted.\n"
         "  Event: " evt "\n"
         "  Value: " val))))

(defn- stream-or-finalize-response
  [state evt chunk current-state]
  (when-not (= :body evt)
    (throw (Exception. "Unknown event: " evt)))

  (let [msg (if chunk
              (mk-netty-chunk chunk)
              (and (.chunked? current-state) HttpChunk/LAST_CHUNK))]

    (swap-then!
     state
     (fn [current-state]
       (if chunk
         (let [bytes-sent     (.bytes-to-send current-state)
               bytes-expected (.bytes-expected current-state)
               bytes-to-send  (+ bytes-sent (chunk-size chunk))
               responded?     (= bytes-expected bytes-to-send)]
           (assoc current-state
             :bytes-to-send bytes-to-send
             :responded?    responded?
             :next-dn-fn    (when-not responded?
                              stream-or-finalize-response)))
         (assoc current-state
           :responded? true
           :next-dn-fn nil)))
     (fn [current-state]
       (if (.responded? current-state)
         (finalize-exchange state current-state msg)
         (write-msg state current-state msg))))))

(defn- initialize-response
  [state evt val current-state]
  (when-not (= :response evt)
    (throw
     (Exception.
      (str "Um... responses start with the head?\n"
           "  Event: " evt "\n"
           "  Value: " val))))

  (let [[status hdrs body] val
        hdrs               (or hdrs {})
        bytes-expected     (content-length hdrs)
        bytes-to-send      (chunk-size body)
        msg                (resp->netty-resp val)]

    ;; 100 responses mean there will be other responses
    ;; following. When this is the final response header, track the
    ;; number of bytes about to be sent as well as the number of
    ;; bytes to expect as specified by the content-length
    ;; header. The HTTP exchange is considered complete unless the
    ;; body is specifically chunked. If there is no content-length
    ;; or transfer-encoding: chunked header, then the HTTP exchange
    ;; will be finalized by closing the connection.

    (when (and (= 100 status) (not (.expects-100? current-state)))
      (throw (Exception. "Not expecting a 100 Continue response.")))

    (swap-then!
     state
     (fn [current-state]
       (if (= 100 status)
         (assoc current-state :expects-100? false)
         (assoc current-state
           :bytes-to-send  bytes-to-send
           :bytes-expected bytes-expected
           :responded?     (not= :chunked body)
           :streaming?     (= :chunked body)
           :chunked?       (= (hdrs "transfer-encoding") "chunked")
           :next-dn-fn     (when (= :chunked body) stream-or-finalize-response)
           :keepalive?     (and (.keepalive? current-state)
                                (not= "close" (hdrs "connection"))
                                (or (hdrs "content-length")
                                    (= (hdrs "transfer-encoding") "chunked"))))))

     (fn [current-state]
       (if (.responded? current-state)
         (finalize-exchange state current-state msg)
         (write-msg state current-state msg))))))

(defn- handle-err
  [state err current-state]
  (let [upstream (.upstream current-state)
        channel  (.ch current-state)]

    (debug {:msg "Handling error" :event err :state current-state})

    (swap-then!
     state
     (fn [current-state]
       (assoc current-state
          :aborting?  true
          :upstream   nil
          :next-up-fn nil))

     (fn [current-state]
       ;; Clear any timeouts for the current connection since we're
       ;; about to close it
       (clear-timeout state current-state)

       (if-let [last-write (.last-write current-state)]
         (.addListener last-write netty/close-channel-future-listener)
         (when channel (.close channel)))

       ;; If there still is an upstream handler, send an abort message
       (when upstream
         (try (upstream :abort err)
              (catch Exception _ nil)))))))

(defn- downstream-fn
  [state]
  ;; The state of the response needs to be tracked
  (swap! state #(assoc % :next-dn-fn initialize-response))

  (fn [evt val]
    (let [current-state @state]

      (debug {:msg   "Downstream event"
              :event [evt val]
              :state current-state})

      (when-not (.aborting? current-state)
        (cond
         (= evt :pause)
         (when (.upstream current-state)
           (.setReadable (.ch current-state) false))

         (= evt :resume)
         (when (.upstream current-state)
           (.setReadable (.ch current-state) true))

         (= evt :abort)
         (handle-err state val current-state)

         (or (= evt :response) (= evt :body))
         (if (.upstream current-state)
           (when-let [next-dn-fn (.next-dn-fn current-state)]
             (bump-timeout state current-state :exchange)
             (next-dn-fn state evt val current-state))
           (throw (Exception.
                   (str "Not callable until request is sent.\n"
                        "Event: " evt "\n"
                        "Value: " val)))))))
    true))

(defn- waiting-for-response
  "The HTTP request has been processed and the response is pending.
   In this state, any further downstream messages are unexpected
   since pipelining is not (yet?) supported. Also, if the connection
   does not support keep alives, the connection will get into this state."
  [_ msg _]
  (throw (Exception.
          (str "Not expecting a message right now.\n"
               "Message: " msg))))

(defn- stream-request-body
  [state ^HttpChunk chunk current-state]
  (let [upstream (.upstream current-state)]
    (if (.isLast chunk)
      (do
        (when upstream
          (debug {:msg "Sending upstream" :event [:body nil]})
          (upstream :body nil))
        (swap-then!
         state
         #(assoc % :next-up-fn waiting-for-response)
         #(finalize-exchange state % nil)))
      (when upstream
        (debug {:msg "Sending upstream" :event [:body (.getContent chunk)]})
        (upstream :body (.getContent chunk))))))

(defn- awaiting-100-continue
  [state chunk current-state]
  (swap! state #(assoc % :next-up-fn stream-request-body))
  (stream-request-body state chunk current-state))

(defn- incoming-request
  [state ^HttpRequest msg {app :app :as current-state}]
  (let [request-id (gen-uuid)]
    ;; First set the states
    (swap-then!
     state
     (fn [current-state]
       (assoc current-state
         :expects-100? (HttpHeaders/is100ContinueExpected msg)
         :request-id   request-id
         :next-up-fn
         (if (.isChunked msg)
           (if (HttpHeaders/is100ContinueExpected msg)
             awaiting-100-continue
             stream-request-body)
           waiting-for-response)

         :keepalive?
         (and (.keepalive? current-state)
              (HttpHeaders/isKeepAlive msg))))
     (fn [current-state]
       ;; Initialize the application
       (let [upstream (app (downstream-fn state))
             ch (.ch current-state)
             request (netty-req->req msg ch request-id)]
         (swap! state #(assoc % :upstream upstream))
         ;; Although technically possible, the applications
         ;; should not pause the exchange until after the
         ;; request has been sent.
         (debug {:msg "Sending upstream" :event [:request request]})
         (upstream :request request)
         #(finalize-exchange state % nil))))))

(defn- handle-ch-interest-change
  [state _ writable? current-state]
  (when-let [upstream (.upstream current-state)]
    (when-not (= (.isWritable (.ch current-state)) @writable?)
      (swap-then!
       writable? not
       #(try
          (debug {:msg "Sending upstream" :event [(if % :resume :pause) nil]})
          (upstream (if % :resume :pause) nil)
          (catch Exception err
            (handle-err state err current-state)))))))

(defn- netty-bridge
  [app opts]
  "Bridges the netty pipeline API to the picard API. This is done with
   a simple state machine that is tracked with an atom."
  (let [state     (atom (mk-initial-state app opts))
        writable? (atom true)]
    (netty/upstream-stage
     (fn [ch evt]
       (let [current-state @state]
         (debug {:msg  "Netty event"
                 :event evt
                 :state current-state})
         (cond-let
          ;; An actual HTTP message has been received
          [msg (netty/message-event evt)]
          (try
            (bump-timeout state current-state :exchange)
            ((.next-up-fn current-state) state msg current-state)
            (catch Exception err
              (handle-err state err current-state)))

          ;; The channel interest has changed to writable
          ;; or not writable
          [[ch-state val] (netty/channel-state-event evt)]
          (cond
           (= ch-state ChannelState/INTEREST_OPS)
           (handle-ch-interest-change state val writable? current-state)

           ;; Connecting the channel - stash it.
           (and (= ch-state ChannelState/CONNECTED) val)
           (do
             (bump-timeout state current-state :exchange)
             (swap! state (fn [current-state] (assoc current-state :ch ch))))

           (= ch-state ChannelState/CONNECTED)
           (do
             (if (or (exchange-finished? current-state)
                     (nil? (.upstream current-state))
                     (.aborting? current-state))
               ;; Gracefully closed connection
               (clear-timeout state current-state)

               ;; Scumbag client, says it wants an HTTP resource,
               ;; disconnects mid exchange.
               (handle-err
                state
                (IOException. "Connection reset by peer")
                current-state))))

          [err (netty/exception-event evt)]
          (when-not (or (exchange-finished? current-state)
                        (instance? IOException err))
            (handle-err state err current-state))

          :else
          (when (netty/unknown-channel-event? evt)
            (when-let [upstream (.upstream current-state)]
              (upstream :netty-event evt)))))))))

(defn- create-pipeline
  [app opts]
  (netty/create-pipeline
   :decoder (HttpRequestDecoder.)
   :encoder (HttpResponseEncoder.)
   :pauser  (netty/message-pauser)
   :handler (netty-bridge app opts)))

(def stop netty/shutdown)

(def default-options
  {:port      4040
   :timeout   30
   :keepalive 60})

(defn start
  "Starts an HTTP server on the specified port."
  ([app] (start app {}))
  ([app opts]
     (let [opts (merge default-options opts)]
       (merge
        (netty/start-server #(create-pipeline app opts) opts)
        {::options opts}))))

(defn restart
  ([server app] (restart server app {}))
  ([{prev-opts ::options :as server} app opts]
     (let [opts (merge default-options prev-opts opts)]
       (merge
        (netty/restart-server server #(create-pipeline app opts) opts)
        {::options opts}))))
