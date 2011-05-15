(ns picard.server
  (:use [picard.utils])
  (:require
   [clojure.string :as str]
   [picard]
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
    HttpVersion]))

(defrecord State
    [app
     ch
     keepalive?
     chunked?
     streaming?
     responded?
     next-up-fn
     next-dn-fn
     upstream
     last-write
     options])

;; Declare some functions in advance -- letfn might be better
(declare incoming-request stream-request-body waiting-for-response)

(defn- mk-initial-state
  ([app options] (mk-initial-state app options nil))
  ([app options ch]
     (State. app               ;; Application for the server
             ch                ;; Netty channel
             true              ;; Is the exchange keepalive?
             nil               ;; Is the request chunked?
             nil               ;; Is the response streaming?
             nil               ;; Has the response been sent?
             incoming-request  ;; Next upstream event handler
             nil               ;; Next downstream event handler
             nil               ;; Upstream handler (external interface)
             nil               ;; Last write to the channel
             options)))         ;; Server options

(defn- fresh-state
  [state]
  (mk-initial-state (.app state) (.options state) (.ch state)))

(defn- finalize-exchange
  [state current-state]
  (when (= incoming-request (.next-up-fn current-state))
    (swap! state fresh-state)
    (let [last-write (if (and (.streaming? current-state)
                              (.chunked? current-state))
                       (.write (.ch current-state) HttpChunk/LAST_CHUNK)
                       (.last-write current-state))]
      (when-not last-write
        (throw (Exception. "Somehow the last-write is nil")))

      (when-not (.keepalive? current-state)
        (.addListener last-write netty/close-channel-future-listener)))))

(defn- aborted-req
  [_ _ _ _]
  (throw (Exception. "This request has been aborted")))

(defn- finalize-resp
  [state _]
  (swap-then!
   state
   (fn [current-state]
     (if (= waiting-for-response (.next-up-fn current-state))
       (assoc current-state
         :next-up-fn incoming-request)
       (assoc current-state
         :next-dn-fn nil
         :responded? true)))
   #(finalize-exchange state %)))

(defn- stream-or-finalize-resp
  [state evt val current-state]
  (cond
   (= :body evt)
   (let [write (.write (.ch current-state) (mk-netty-chunk val))]
     (swap!
      state
      (fn [current-state]
        (assoc current-state
          :next-dn-fn stream-or-finalize-resp
          :last-write write))))

   (= :done evt)
   (finalize-resp state current-state)

   :else
   (throw (Exception. "Unknown event: " evt))))

(defn- initialize-resp
  [state evt [_ hdrs body :as val] current-state]
  (when-not (= :response evt)
    (throw (Exception. "Um... responses start with the head?")))
  (let [write (.write (.ch current-state) (resp->netty-resp val))]
    (swap!
     state
     (fn [current-state]
       (assoc current-state
         :next-dn-fn (if (= :chunked body) stream-or-finalize-resp)
         :streaming? (= :chunked body)
         :chunked?   (= (hdrs "transfer-encoding") "chunked")
         :last-write write
         ;; TODO: Handle HTTP 1.0 responses
         :keepalive? (and (.keepalive? current-state)
                          (not= "close" (hdrs "connection"))
                          (or (hdrs "content-length")
                              (= (hdrs "transfer-encoding") "chunked"))))))
    (if (not= :chunked body)
      (finalize-resp state current-state))))

(defn- downstream-fn
  [state]
  ;; The state of the response needs to be tracked
  (swap!
   state
   (fn [current-state]
     (assoc current-state :next-dn-fn initialize-resp)))

  (fn [evt val]
    (let [current-state @state]
      (cond
       (= evt :pause)
       (.setReadable (.ch current-state) false)

       (= evt :resume)
       (.setReadable (.ch current-state) true)

       :else
       (when-let [next-dn-fn (.next-dn-fn current-state)]
         (next-dn-fn state evt val current-state))))
    true))

(defn- waiting-for-response
  "The HTTP request has been processed and the response is pending.
   In this state, any further downstream messages are unexpected
   since pipelining is not (yet?) supported. Also, if the connection
   does not support keep alives, the connection will get into this state."
  [_ _ _]
  (throw (Exception. "Not expecting a message right now")))

(defn- handle-err
  [state err current-state]
  (when (.upstream current-state)
    (try
      ((.upstream current-state) :abort err)
      (catch Exception e nil))
    (swap!
     state
     (fn [current-state]
       (assoc current-state
         :next-up-fn nil
         :upstream   nil))))
  (if (.last-write current-state)
    (.addListener (.last-write current-state)
                  netty/close-channel-future-listener)
    (.close (.ch current-state))))

(defn- handling-request
  "The initial HTTP request is being handled and we're not expected
   further messages"
  [_ _ _]
  (throw (Exception. "Not expecting a message right now")))

(defn- transition-from-req-done
  "State transition from an HTTP request has finished being processed.
   If the response has already been sent, then the next state is to
   listen for new requests."
  [state current-state]
  (swap-then!
   state
   (fn [current-state]
     (if (.responded? current-state)
       (assoc current-state :next-up-fn incoming-request)
       (assoc current-state :next-up-fn waiting-for-response)))
   #(finalize-exchange state %)))

(defn- transition-to-streaming-body
  [state]
  (swap!
   state
   (fn [current-state]
     (assoc current-state :next-up-fn stream-request-body))))

(defn- stream-request-body
  [state chunk current-state]
  (let [upstream (.upstream current-state)]
    (if (.isLast chunk)
      (do
        (upstream :done nil)
        (transition-from-req-done state current-state))
      (upstream :body (.getContent chunk)))))

(defn- incoming-request
  [state msg {app :app :as current-state}]
  ;; First track that a request is currently being handled
  (swap!
   state
   (fn [current-state]
     (assoc current-state :next-up-fn handling-request)))
  ;; Now, handle the request
  (try
    (let [upstream (app (downstream-fn state) (netty-req->req msg))]
      ;; Add the upstream handler to the state
      (swap!
       state
       (fn [current-state]
         ;; TODO: refactor this?
         (assoc current-state
           :keepalive? (and (.keepalive? current-state)
                            (HttpHeaders/isKeepAlive msg))
           :upstream upstream)))
      ;; Send the HTTP headers upstream
      (if (.isChunked msg)
        (transition-to-streaming-body state)
        (transition-from-req-done state current-state)))
    (catch Exception err
      (handle-err state err current-state))))

(defn- handle-ch-interest-change
  [state writable? current-state]
  (when-let [upstream (.upstream current-state)]
    (when-not (= (.isWritable (.ch current-state)) @writable?)
      (swap-then!
       writable? not
       #(try
          (upstream (if % :resume :pause) nil)
          (catch Exception err
            (handle-err state err current-state)))))))

(defn- handle-ch-disconnected
  [state current-state]
  (when-let [upstream (.upstream current-state)]
    (upstream :abort nil)
    (swap!
     state
     (fn [current-state]
       (assoc current-state :next-dn-fn aborted-req)))))

(defn- netty-bridge
  [app opts]
  "Bridges the netty pipeline API to the picard API. This is done with
   a simple state machine that is tracked with an atom."
  (let [state     (atom (mk-initial-state app opts))
        writable? (atom true)]
    (netty/upstream-stage
     (fn [ch evt]
       (let [current-state @state]
         (cond-let
          ;; An actual HTTP message has been received
          [msg (netty/message-event evt)]
          ((.next-up-fn current-state) state msg current-state)

          ;; The channel interest has changed to writable
          ;; or not writable
          [[ch-state val] (netty/channel-state-event evt)]
          (cond
           (= ch-state ChannelState/INTEREST_OPS)
           (handle-ch-interest-change state writable? current-state)

           ;; Connecting the channel - stash it.
           (and (= ch-state ChannelState/CONNECTED) val)
           (swap! state (fn [current-state] (assoc current-state :ch ch)))

           (= ch-state ChannelState/CONNECTED)
           (handle-ch-disconnected state current-state))

          [err (netty/exception-event evt)]
          (handle-err state err current-state)))))))

(defn- create-pipeline
  [app]
  (netty/create-pipeline
   :decoder (HttpRequestDecoder.)
   :encoder (HttpResponseEncoder.)
   :handler (netty-bridge app {})))

(def stop netty/shutdown)

(defn start
  "Starts an HTTP server on the specified port."
  ([app]
     (start app {}))
  ([app opts]
     (netty/start-server
      #(create-pipeline app)
      (merge {:port 4040} opts))))
