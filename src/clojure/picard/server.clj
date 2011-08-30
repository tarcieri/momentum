(ns picard.server
  (:use
   [picard.utils :rename {debug debug*} :exclude [cond-let]]
   [clojure.contrib.cond])
  (:require
   [clojure.string :as str]
   [picard.net     :as netty])
  (:import
   [org.jboss.netty.buffer
    ChannelBuffer]
   [org.jboss.netty.channel
    Channel
    ChannelFuture
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
    [ch
     keepalive?
     chunked?
     head?
     responded?
     request-id
     next-up-fn
     next-dn-fn
     upstream
     bytes-expected
     bytes-to-send
     last-write
     options
     aborting?
     interest-ops-lock
     timeout
     keepalive-timeout])

;; Declare some functions in advance
(declare
 initialize-response
 stream-request-body
 awaiting-100-continue
 waiting-for-response
 handle-err)

(def global-timer netty/global-timer)

(defmacro debug
  [& msgs]
  `(debug* :server ~@msgs))

(defn- initialize-exchange-state
  [ch keepalive-timeout options]
  (State. ch                  ;; Netty channel
          true                ;; Is the exchange keepalive?
          nil                 ;; Is the request chunked?
          false               ;; Is this a HEAD request?
          nil                 ;; Has the response been sent?
          nil                 ;; Request ID
          nil                 ;; Next upstream event handler
          initialize-response ;; Next downstream event handler
          nil                 ;; Upstream handler (external interface)
          nil                 ;; Bytes expected
          0                   ;; Bytes sent
          nil                 ;; Last write to the channel
          options             ;; Server options
          false               ;; Are we aborting the exchange?
          [true 0]            ;; Interest ops lock
          nil                 ;; Timeout reference
          keepalive-timeout)) ;; Channel keepalive timer atom

(defn- content-length
  [hdrs]
  (when-let [content-length (hdrs "content-length")]
    (if (number? content-length)
      (long content-length)
      (Long. (str content-length)))))

(defn- chunk-size
  [chunk]
  (cond
   (or (= :chunked chunk) (not chunk))
   0

   (instance? ChannelBuffer chunk)
   (.readableBytes ^ChannelBuffer chunk)

   :else
   (count chunk)))

(defmacro awaiting-100-continue?
  [current-state]
  `(= awaiting-100-continue (.next-up-fn ~current-state)))

(defmacro awaiting-response?
  [current-state]
  `(= waiting-for-response (.next-up-fn ~current-state)))

(defn- exchange-finished?
  [^State current-exchange]
  (or (nil? current-exchange)
      (.aborting? current-exchange)
      (and (.responded? current-exchange)
           (or (awaiting-response? current-exchange)
               (awaiting-100-continue? current-exchange)))))

(defmacro exchange-in-progress?
  [current-exchange]
  `(not (exchange-finished? ~current-exchange)))

(defn- throw-http-pipelining-exception
  []
  (throw (Exception. (str "Not expecting an HTTP request right now, "
                          "pipelining is not yet supported."))))

;; A lock must be thrown around timeout handling because otherwise
;; there could be a race condition where old timeouts are lost before
;; new ones are set.
;; TODO: Eventually we want to move the lock around the entire state change.
(defn- clear-timeout*
  [^State current-state]
  (when-let [old-timeout (.timeout current-state)]
    (netty/cancel-timeout old-timeout)))

(defn- clear-timeout
  [state]
  (locking state
    (clear-timeout* @state)))

(defn- bump-timeout
  [state]
  (locking state
    (let [current-state ^State @state]
      (clear-timeout* current-state)
      (let [new-timeout
            (netty/on-timeout
             global-timer (* (:timeout (.options current-state)) 1000)
             #(handle-err
               state
               (Exception. "Server HTTP exchange timed out") current-state))]
        (swap! state #(assoc % :timeout new-timeout))))))

(defn- clear-keepalive-timeout
  [keepalive-timeout-atom]
  (when-let [keepalive-timeout @keepalive-timeout-atom]
    (netty/cancel-timeout keepalive-timeout)))

(defn- bump-keepalive-timeout
  ([^State current-state]
     (bump-keepalive-timeout
      (.keepalive-timeout current-state)
      (.ch current-state)
      (.options current-state)))
  ([keepalive-timeout-atom ^Channel ch options]
     (locking keepalive-timeout-atom
       (clear-keepalive-timeout keepalive-timeout-atom)
       (reset!
        keepalive-timeout-atom
        (netty/on-timeout
         global-timer (* (:keepalive options) 1000)
         (fn []
           (debug {:msg "Connection reached max keepalive time." :event ch})
           (.close ch)))))))

(defn- write-msg
  [state ^State current-state msg]
  (let [channel ^Channel (.ch current-state)
        last-write (.write channel msg)]
    (swap! state #(assoc % :last-write last-write))
    last-write))

(defn- write-last-msg
  [state ^State current-state msg close-channel?]
  (let [last-write
        (if msg
          (write-msg state current-state msg)
          (.last-write current-state))]
    (when close-channel?
      (.addListener
       ^ChannelFuture last-write netty/close-channel-future-listener))))

(defn- finalize-exchange
  [state ^State current-state last-msg]
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
        (clear-timeout state)

        (if (.keepalive? current-state)
          (do
            (debug {:msg "Keeping connection alive" :state current-state})
            (bump-keepalive-timeout current-state)
            (write-last-msg state current-state last-msg false))
          (do
            (debug {:msg "Killing connection" :state current-state})
            (write-last-msg state current-state last-msg true))))
      (write-last-msg state current-state last-msg false))))

(defn- stream-or-finalize-response
  [state evt chunk ^State current-state]
  (when-not (= :body evt)
    (throw (Exception. "Unknown event: " evt)))

  (let [msg (if chunk
              (mk-netty-chunk chunk)
              (and (.chunked? current-state) HttpChunk/LAST_CHUNK))]

    (swap-then!
     state
     (fn [^State current-state]
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
     (fn [^State current-state]
       (if (.responded? current-state)
         (finalize-exchange state current-state msg)
         (write-msg state current-state msg))))))

(defn- initialize-response
  [state evt val ^State current-state]
  (when-not (= :response evt)
    (throw
     (Exception.
      (str "Um... responses start with the head?\n"
           "  Event: " evt "\n"
           "  Value: " val))))

  (let [[status hdrs body] val
        hdrs               (or hdrs {})
        body               (and (not (.head? current-state)) body)
        bytes-expected     (content-length hdrs)
        bytes-to-send      (chunk-size body)
        msg                (resp->netty-resp status hdrs body)]

    ;; 100 responses mean there will be other responses
    ;; following. When this is the final response header, track the
    ;; number of bytes about to be sent as well as the number of
    ;; bytes to expect as specified by the content-length
    ;; header. The HTTP exchange is considered complete unless the
    ;; body is specifically chunked. If there is no content-length
    ;; or transfer-encoding: chunked header, then the HTTP exchange
    ;; will be finalized by closing the connection.

    (when (and (= 100 status) (not (awaiting-100-continue? current-state)))
      (throw (Exception. "Not expecting a 100 Continue response.")))

    ;; 204 and 304 responses MUST NOT have a response body, so if we
    ;; get one, throw an exception.
    (when (and (or (= 204 status) (= 304 status))
               (not (empty? body)))
      (throw (Exception. (str status " responses must not include a body."))))

    (swap-then!
     state
     (fn [^State current-state]
       (if (= 100 status)
         (assoc current-state :next-up-fn stream-request-body)
         (let [responded? (or (.head? current-state) (not= :chunked body))]
           (assoc current-state
             :bytes-to-send  bytes-to-send
             :bytes-expected bytes-expected
             :responded?     responded?
             ;; TODO, this isn't exactly correct since 304 responses
             ;; won't send the body, and we also need to handle the
             ;; case of transfer-encoding: chunked w/ a single chunk
             ;; passed with the response.
             :chunked?       (= (hdrs "transfer-encoding") "chunked")
             :next-dn-fn     (when (not responded?) stream-or-finalize-response)
             :keepalive?     (and (.keepalive? current-state)
                                  (not= "close" (hdrs "connection"))
                                  (or (hdrs "content-length")
                                      (= (hdrs "transfer-encoding") "chunked")
                                      (no-response-body? status)))))))

     (fn [^State current-state]
       (if (.responded? current-state)
         (finalize-exchange state current-state msg)
         (write-msg state next-up msg current-state))))))

(defn- handle-err
  [state err ^State current-state]
  (when-not (.aborting? current-state)
    (swap-then!
     state
     #(assoc % :aborting? true)
     (fn [^State current-state]
       (debug {:msg "Handling error" :event err :state current-state})
       (let [channel ^Channel (.ch current-state)]
         ;; Clear any timeouts for the current connection since we're
         ;; about to close it
         (clear-timeout state)
         (clear-keepalive-timeout (.keepalive-timeout current-state))

         (if-let [last-write ^ChannelFuture (.last-write current-state)]
           (.addListener last-write netty/close-channel-future-listener)
           (when (.isOpen channel)
             (.close channel)))

         (try ((.upstream current-state) :abort err)
              (catch Exception _ nil)))))))

(defn- mk-downstream-fn
  [state]
  (fn [evt val]
    (let [current-state ^State @state]

      (debug {:msg   "Downstream event"
              :event [evt val]
              :state current-state})

      (when-not (.aborting? current-state)
        (cond
         (= evt :pause)
         (when (.upstream current-state)
           (.setReadable ^Channel (.ch current-state) false))

         (= evt :resume)
         (when (.upstream current-state)
           (.setReadable ^Channel (.ch current-state) true))

         (= evt :abort)
         (handle-err state val current-state)

         (or (= evt :response) (= evt :body))
         (if (.upstream current-state)
           (when-let [next-dn-fn (.next-dn-fn current-state)]
             (bump-timeout state)
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
  [state ^HttpChunk chunk ^State current-state]
  (let [upstream (.upstream current-state)]
    (if (.isLast chunk)
      (do
        (debug {:msg "Sending upstream" :event [:body nil]})
        (upstream :body nil)
        (swap-then!
         state
         #(assoc % :next-up-fn waiting-for-response)
         #(finalize-exchange state % nil)))
      (do
        (debug {:msg "Sending upstream" :event [:body (.getContent chunk)]})
        (upstream :body (.getContent chunk))))))

(defn- awaiting-100-continue
  [state chunk current-state]
  (swap! state #(assoc % :next-up-fn stream-request-body))
  (stream-request-body state chunk current-state))

(defn- initialize-exchange
  [channel-state keepalive-timeout app ch ^HttpRequest netty-request options]
  (let [current-state (initialize-exchange-state ch keepalive-timeout options)
        state         (atom current-state)]

    (reset! channel-state state)
    (clear-keepalive-timeout keepalive-timeout)
    (bump-timeout state)

    (try
      (let [upstream-fn   (app (mk-downstream-fn state))
            request-id    (gen-uuid)
            request       (netty-req->req netty-request ch request-id)
            keepalive?    (HttpHeaders/isKeepAlive netty-request)
            expects-100?  (HttpHeaders/is100ContinueExpected netty-request)
            head?         (= HttpMethod/HEAD (.getMethod netty-request))
            chunked?      (.isChunked netty-request)
            next-up-fn    (cond
                           (not chunked?) waiting-for-response
                           expects-100?   awaiting-100-continue
                           :else          stream-request-body)]

        (swap-then!
         state
         (fn [current-state]
           (assoc current-state
             :keepalive?   keepalive?
             :head?        head?
             :request-id   request-id
             :next-up-fn   next-up-fn
             :upstream     upstream-fn))
         (fn [current-state]
           (debug {:msg   "Sending upstream"
                   :event [:request request]
                   :state current-state})

           (upstream-fn :request request)
           (finalize-exchange state current-state nil))))
      (catch Exception err
        (handle-err state err current-state)))))

(def interest-ops
  {Channel/OP_NONE       :op-none
   Channel/OP_READ       :op-read
   Channel/OP_WRITE      :op-write
   Channel/OP_READ_WRITE :op-read-write})

(defn- handle-ch-interest-change
  [state ^State current-state]
  (try
    (let [upstream-fn (.upstream current-state)
          ch          ^Channel (.ch current-state)]

      (debug {:msg   "Interest Ops Changed"
              :event [:interest-ops (interest-ops (.getInterestOps ch))]
              :state current-state})

      ;; Gotta synchronize this shit
      (swap-then!
       state
       (fn [current-state]
         (let [[writable? count] (.interest-ops-lock current-state)]
           (assoc current-state :interest-ops-lock [writable? (inc count)])))
       (fn [current-state]
         (let [[writable? count] (.interest-ops-lock current-state)]
           ;; When count = 1, the lock has been acquired
           (when (= count 1)
             (loop [writable? writable?]
               (when (and upstream-fn (not= (.isWritable ch) writable?))
                 (swap-then!
                  state
                  (fn [current-state]
                    (let [[writable? count] (.interest-ops-lock current-state)]
                      (assoc current-state :interest-ops-lock [(not writable?) count])))
                  (fn [current-state]
                    (let [[writable?] (.interest-ops-lock current-state)]
                      (debug {:msg   "Sending upstream"
                              :event [(if writable? :resume :pause)]
                              :state current-state})
                      (upstream-fn (if writable? :resume :pause) nil)))))
               (let [[writable? count]
                     (.interest-ops-lock
                      (swap!
                       state
                       (fn [current-state]
                         (let [[writable? count] (.interest-ops-lock current-state)]
                           (assoc current-state
                             :interest-ops-lock [writable? (dec count)])))))]
                 (when (< 0 count)
                   (recur writable?)))))))))
    (catch Exception err
      (handle-err state err @state))))

(defn- handle-netty-event
  [state evt msg ^State current-state]
  (cond
   ;; The only upstream HTTP message that we care about at this point
   ;; are HTTP chunks. Anything else is not recognized by Picard
   ;; server and will be passed upstream in case the application wants
   ;; to handle it.
   (instance? HttpChunk msg)
   (try
     (bump-timeout state)
     ((.next-up-fn current-state) state msg current-state)
     (catch Exception err
       (handle-err state err current-state)))

   ;; The channel's interest ops changed. If the writable op changed
   ;; then we need to send an upstream event to tell the application
   ;; to block / unblock
   (netty/channel-interest-changed-event? evt)
   (handle-ch-interest-change state current-state)

   ;; Scumbag client, says it wants an HTTP resource, disconnects mid exchange
   (netty/channel-disconnected-event? evt)
   (handle-err state (IOException. "Connection reset by peer") current-state)

   ;; We got an exception from netty, so handle it
   (netty/exception-event evt)
   (let [err (netty/exception-event evt)]
     (when-not (instance? IOException err)
       (handle-err state err current-state)))

   ;; Send up events that might be interesting to the application
   (or (netty/unknown-channel-event? evt) msg)
   ((.upstream current-state) :netty-event evt)))

(defn- netty-bridge
  "Bridges the netty pipeline API to the picard API. This is done with
   a simple state machine that is tracked with an atom."
  [app opts]
  ;; I'm not a huge fan of using a separate atom for tracking the
  ;; keepalive timer; however, for now, it's the least of my worries.
  (let [channel-state     (atom nil)
        keepalive-timeout (atom nil)]
    (netty/upstream-stage
     ;; TODO: Well, shit... we're gonna have to figure out how to deal
     ;; with keep alive timeouts again o_O
     (fn [ch evt]
       (let [current-exchange @channel-state
             exchange-state   (and current-exchange @current-exchange)
             event-message    (netty/message-event evt)]

         (debug {:msg   "Netty event"
                 :event evt
                 :state exchange-state})

         (cond
          ;; New HTTP request, so we have to make sure that we are in
          ;; not in the middle of an HTTP exchange and then initialize
          ;; the new exchange
          (instance? HttpRequest event-message)
          (if (exchange-in-progress? exchange-state)
            (throw-http-pipelining-exception)
            (initialize-exchange channel-state keepalive-timeout
                                 app ch event-message opts))

          (exchange-in-progress? exchange-state)
          (handle-netty-event current-exchange evt event-message exchange-state)

          (netty/channel-connect-event? evt)
          (bump-keepalive-timeout keepalive-timeout ch opts)

          (netty/channel-disconnected-event? evt)
          (clear-keepalive-timeout keepalive-timeout)))))))

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
