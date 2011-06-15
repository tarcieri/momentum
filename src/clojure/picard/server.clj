(ns picard.server
  (:use [picard.utils])
  (:require
   [clojure.string :as str]
   [picard.netty :as netty])
  (:import
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

(defrecord State
    [app
     ch
     keepalive?
     chunked?
     streaming?
     responded?
     expects-100?
     next-up-fn
     next-dn-fn
     upstream
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
             incoming-request  ;; Next upstream event handler
             nil               ;; Next downstream event handler
             nil               ;; Upstream handler (external interface)
             nil               ;; Last write to the channel
             options           ;; Server options
             false
             nil)))          ;; Are we aborting the exchange?

(defn- fresh-state
  [state]
  (mk-initial-state (.app state) (.options state) (.ch state)))

(defn- clear-timeout
  [state current-state]
  (when-let [old-timeout (.timeout current-state)]
    (netty/cancel-timeout old-timeout)))

(defn- bump-timeout
  ([state current-state]
     (bump-timeout state (* (:timeout (.options current-state)) 1000) current-state))
  ([state ms current-state]
     (clear-timeout state current-state)
     (let [new-timeout (netty/on-timeout
                        global-timer
                        ms
                        #(handle-err state (Exception. "timeout! facepalm!") @state))]
       (swap! state #(assoc % :timeout new-timeout)))))

(defn- start-keepalive-timer
  [state current-state]
  (bump-timeout state (* (:keepalive (.options current-state)) 1000) current-state))

(defn- exchange-finished?
  [current-state]
  (and (.responded? current-state)
       (or (= waiting-for-response (.next-up-fn current-state))
           (= awaiting-100-continue (.next-up-fn current-state)))))

(defn- maybe-write-chunk-trailer
  [current-state]
  (if (and (.streaming? current-state)
           (.chunked? current-state))
    (assoc current-state
      :last-write (.write (.ch current-state) HttpChunk/LAST_CHUNK))
    current-state))

(defn- finalize-channel
  [current-state]
  (let [last-write (.last-write current-state)]
    (when-not last-write
      (throw (Exception. "Somehow the last-write is nil")))

    (.addListener last-write netty/close-channel-future-listener)))

(defn- finalize-exchange
  [state current-state]
  (when (exchange-finished? current-state)
    (let [current-state (maybe-write-chunk-trailer current-state)
          upstream      (.upstream current-state)]
      (upstream :done nil)
      (clear-timeout state current-state)
      (if (.keepalive? current-state)
        (let [new-state (fresh-state current-state)]
          (reset! state new-state)
          (start-keepalive-timer state current-state))
        (do (swap! state #(assoc % :upstream nil))
            (finalize-channel current-state))))))

(defn- aborted-req
  [_ _ _ _]
  (throw (Exception. "This request has been aborted")))

(defn- stream-or-finalize-response
  [state evt val current-state]
  (when-not (= :body evt)
    (throw (Exception. "Unknown event: " evt)))

  (if val
    ;; There is more coming
    (let [write (.write (.ch current-state) (mk-netty-chunk val))]
      (swap!
       state
       #(assoc %
          :next-dn-fn stream-or-finalize-response
          :last-write write)))

    ;; This is the last chunk
    (swap-then!
     state
     #(assoc % :responded? true :next-dn-fn nil)
     #(finalize-exchange state %))))

(defn- initialize-response
  [state evt [status hdrs body :as val] current-state]
  (when-not (= :response evt)
    (throw (Exception. "Um... responses start with the head?")))

  (when (and (= 100 status) (not (.expects-100? current-state)))
    (throw (Exception. "Not expecting a 100 Continue response.")))

  (let [write (.write (.ch current-state) (resp->netty-resp val))]
    (if (= 100 status)
      (swap! state #(assoc % :expects-100? false))
      (swap-then!
       state
       (fn [current-state]
         (assoc current-state
           :next-dn-fn (when (= :chunked body) stream-or-finalize-response)
           :responded? (not (= :chunked body))
           :streaming? (= :chunked body)
           :chunked?   (= (hdrs "transfer-encoding") "chunked")
           :last-write write
           ;; TODO: Handle HTTP 1.0 responses
           :keepalive? (and (.keepalive? current-state)
                            (not= "close" (hdrs "connection"))
                            (or (hdrs "content-length")
                                (= (hdrs "transfer-encoding") "chunked")))))
       (fn [current-state]
         (when-not (.next-dn-fn current-state)
           (finalize-exchange state current-state)))))))

(defn- handle-err
  [state err _]
  (swap-then!
   state
   #(assoc % :aborting? true)
   (fn [current-state]
     ;; If there is an upstream, try sending an abort event up
     (when (.upstream current-state)
       (try ((.upstream current-state) :abort err)
            (catch Exception _ nil))
       (swap! state #(assoc % :next-up-fn nil :upstream nil)))

     ;; Clear out any timeouts
     (clear-timeout state current-state)

     ;; Finally, close the connection
     (if (.last-write current-state)
       (.addListener (.last-write current-state)
                     netty/close-channel-future-listener)
       (.close (.ch current-state))))))

(defn- downstream-fn
  [state]
  ;; The state of the response needs to be tracked
  (swap! state #(assoc % :next-dn-fn initialize-response))

  (fn [evt val]
    (debug "SRV DN-STRM: " [evt val])
    (let [current-state @state]
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
             (bump-timeout state current-state)
             (next-dn-fn state evt val current-state))
           (throw (Exception. (str "Not callable until request is sent.\n"
                                   "Event: " evt "\n"
                                   "Value: " val)))))))
    true))

(defn- waiting-for-response
  "The HTTP request has been processed and the response is pending.
   In this state, any further downstream messages are unexpected
   since pipelining is not (yet?) supported. Also, if the connection
   does not support keep alives, the connection will get into this state."
  [_ _ _]
  (throw (Exception. "Not expecting a message right now")))

(defn- stream-request-body
  [state chunk current-state]
  (let [upstream (.upstream current-state)]
    (if (.isLast chunk)
      (do (when upstream (upstream :body nil))
          (swap-then!
           state
           #(assoc % :next-up-fn waiting-for-response)
           #(finalize-exchange state %)))
      (when upstream (upstream :body (.getContent chunk))))))

(defn- awaiting-100-continue
  [state chunk current-state]
  (swap! state #(assoc % :next-up-fn stream-request-body))
  (stream-request-body state chunk current-state))

(defn- incoming-request
  [state msg {app :app :as current-state}]
  (try
    ;; First set the states
    (swap-then!
     state
     (fn [current-state]
       (assoc current-state
         :expects-100? (HttpHeaders/is100ContinueExpected msg)
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
             ch (.ch current-state)]
         (swap! state #(assoc % :upstream upstream))
         ;; Although technically possible, the applications
         ;; should not pause the exchange until after the
         ;; request has been sent.
         (upstream :request (netty-req->req msg ch))
         #(finalize-exchange state %))))
    (catch Exception err
      (handle-err state err current-state))))

(defn- handle-ch-interest-change
  [state _ writable? current-state]
  (when-let [upstream (.upstream current-state)]
    (when-not (= (.isWritable (.ch current-state)) @writable?)
      (swap-then!
       writable? not
       #(try
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
       (debug "SRV NETTY EVT: " evt)
       (let [current-state @state]
         (cond-let
          ;; An actual HTTP message has been received
          [msg (netty/message-event evt)]
          (try
            (bump-timeout state current-state)
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
             (bump-timeout state current-state)
             (swap! state (fn [current-state] (assoc current-state :ch ch))))

           (= ch-state ChannelState/CONNECTED)
           (handle-err state (IOException. "Connection reset by peer")
                       current-state))

          [err (netty/exception-event evt)]
          (when-not (instance? IOException err)
            (handle-err state err current-state))))))))

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
  ([app]
     (start app {}))
  ([app opts]
     (let [opts (merge default-options opts)]
       (netty/start-server #(create-pipeline app opts) opts))))
