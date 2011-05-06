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

;; Declare some functions in advance -- letfn might be better
(declare incoming-request stream-request-body waiting-for-response)

(defn- fresh-args
  [args]
  (-> args
      (select-keys [:app :opts])
      (assoc :last-args args :keepalive? true)))

(defn- finalize-ch
  [ch {:keys [keepalive? streaming? chunked? last-write]}]
  (let [last-write (if (and streaming? chunked?)
                     (.write ch HttpChunk/LAST_CHUNK)
                     last-write)]
    (when-not last-write
      (throw (Exception. "Somehow the last-write is nil")))

    (when-not keepalive?
      (.addListener last-write netty/close-channel-future-listener))))

(defn- finalized-resp
  [_ _]
  (throw (Exception. "This response is finished")))

(defn- aborted-req
  [_ _ _ _]
  (throw (Exception. "This request has been aborted")))

(defn- finalize-resp
  [state ch]
  (swap-then!
   state
   (fn [[up-f _ args]]
     (if (= up-f waiting-for-response)
       [incoming-request nil (fresh-args args)]
       [up-f finalize-resp (assoc args :responded? true)]))
   (fn [[next-fn _ {args :last-args}]]
     (when (= next-fn incoming-request)
       (finalize-ch ch args)))))

(defn- stream-or-finalize-resp
  [state ch evt val]
  (cond
   (= :body evt)
   (let [write (.write ch (mk-netty-chunk val))]
     (swap!
      state
      (fn [[up-f _ args]]
        [up-f stream-or-finalize-resp (assoc args :last-write write)])))

   (= :done evt)
   (finalize-resp state ch)

   :else
   (throw (Exception. "Unknown event: " evt))))

;; TODO: Handle HTTP 1.0 responses
(defn- is-keepalive?
  [req-keepalive? hdrs]
  (and req-keepalive?
       (not= "close" (hdrs "connection"))
       (or (hdrs "content-length")
           (= (hdrs "transfer-encoding") "chunked"))))

(defn- initialize-resp
  [state ch evt [_ hdrs body :as val]]
  (when-not (= :response evt)
    (throw (Exception. "Um... responses start with the head?")))
  (let [write (.write ch (resp->netty-resp val))]
    (swap! state
           (fn [[up-f dn-f {keepalive? :keepalive? :as args}]]
             [up-f
              (if (= :chunked body) stream-or-finalize-resp nil)
              (assoc args
                :streaming? (= :chunked body)
                :chunked?   (= (hdrs "transfer-encoding") "chunked")
                :keepalive? (is-keepalive? keepalive? hdrs)
                :last-write write)]))
    (if (not= :chunked body)
      (finalize-resp state ch))))

(defn- downstream-fn
  [state ^Channel ch]
  ;; The state of the response needs to be tracked
  (swap!
   state
   (fn [[up-f _ args]] [up-f initialize-resp args]))

  (fn [evt val]
    (cond
     (= evt :pause)
     (.setReadable ch false)

     (= evt :resume)
     (.setReadable ch true)

     :else
     (let [[_ dn-f _] @state]
       (when dn-f
         (dn-f state ch evt val))))
    true))

(defn- waiting-for-response
  "The HTTP request has been processed and the response is pending.
   In this state, any further downstream messages are unexpected
   since pipelining is not (yet?) supported. Also, if the connection
   does not support keep alives, the connection will get into this state."
  [_ _ _ _]
  (throw (Exception. "Not expecting a message right now")))

(defn- handle-err
  [state ch err]
  (.printStackTrace err)
  (let [[_ _ {upstream :upstream}] @state]
    (when upstream
      (try
        (upstream :abort err)
        (catch Exception e nil))
      (swap!
       state
       (fn [[_ _ args]]
         [nil aborted-req (dissoc args :upstream)])))
    (.close ch)))

(defn- handling-request
  "The initial HTTP request is being handled and we're not expected
   further messages"
  [_ _ _ _]
  (throw (Exception. "Not expecting a message right now")))

(defn- transition-from-req-done
  "State transition from an HTTP request has finished being processed.
   If the response has already been sent, then the next state is to
   listen for new requests."
  [state ch]
  (swap-then!
   state
   (fn [[_ dn-f args]]
     (if (args :responded?)
       [incoming-request dn-f (fresh-args args)]
       [waiting-for-response dn-f args]))
   (fn [[next-fn _ {args :last-args :as lol}]]
     (when (= next-fn incoming-request)
       (finalize-ch ch args)))))

(defn- transition-to-streaming-body
  [state]
  (swap! state (fn [[_ dn-f args]] [stream-request-body dn-f args])))

(defn- stream-request-body
  [state ch ^HttpChunk chunk {upstream :upstream}]
  (if (.isLast chunk)
    (do
      (upstream :done nil)
      (transition-from-req-done state ch))
    (upstream :body (.getContent chunk))))

(defn- incoming-request
  [state ch ^HttpMessage msg {app :app :as args}]
  (swap! state (fn [[_ dn-f args]] [handling-request dn-f args]))
  (try
    (let [upstream   (app (downstream-fn state ch) (netty-req->req msg))]
      ;; Add the upstream handler to the state
      (swap! state (fn [[up-f dn-f {keepalive? :keepalive? :as args}]]
                     [up-f dn-f
                      (assoc args
                        :keepalive? (and keepalive? (HttpHeaders/isKeepAlive msg))
                        :upstream   upstream)]))

      ;; Send the HTTP headers upstream
      (if (.isChunked msg)
        (transition-to-streaming-body state)
        (transition-from-req-done state ch)))
    (catch Exception err
      (handle-err state ch err))))

(defn- handle-ch-interest-change
  [ch [_ _ {upstream :upstream}] writable?]
  (when (and upstream (not= (.isWritable ch) @writable?))
    (swap-then! writable? not #(upstream (if % :resume :pause) nil))))

(defn- handle-ch-disconnected
  [state]
  (let [[_ _ {upstream :upstream}] @state]
    (when upstream
      (upstream :abort nil)
      (swap!
       state
       (fn [[up-f _ args]]
         [up-f aborted-req args])))))

(defn- netty-bridge
  [app opts]
  "Bridges the netty pipeline API to the picard API. This is done with
   a simple state machine that is tracked with an atom. The atom is
   always a 3-tuple: [ next-fn args responded? ].

   next-fn:    The function to call when the next message is received. These
               functions must return a function that transitions the current
               state to the next state using swap!

   args:       The first argument (usually a vector) that gets passed to
               next-fn when it is invoked.

   responded?: Whether or not the application has responded to the current
               request"
  (let [state     (atom [incoming-request nil {:app app :opts opts :keepalive? true}])
        writable? (atom true)]
    (netty/upstream-stage
     (fn [ch evt]
       (cond-let
        ;; An actual HTTP message has been received
        [msg (netty/message-event evt)]
        (let [[next-fn _ args] @state]
          (next-fn state ch msg args))

        ;; The channel interest has changed to writable
        ;; or not writable
        [[ch-state val] (netty/channel-state-event evt)]
        (cond
         (= ch-state ChannelState/INTEREST_OPS)
         (handle-ch-interest-change ch @state writable?)

         (and (= ch-state ChannelState/CONNECTED)
              (nil? val))
         (handle-ch-disconnected state))

        [err (netty/exception-event evt)]
        (handle-err state ch err))))))

(defn- create-pipeline
  [app]
  (netty/create-pipeline
   :decoder (HttpRequestDecoder.)
   :encoder (HttpResponseEncoder.)
   :handler (netty-bridge app {})))

(defn start
  "Starts an HTTP server on the specified port."
  ([app]
     (start app {}))
  ([app opts]
     (netty/start-server
      #(create-pipeline app)
      (merge {:port 4040} opts))))
