(ns picard.server
  (:require
   [clojure.string :as str]
   [picard]
   [picard.netty :as netty]
   [picard.utils :as utils])
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

(defmacro swap-then!
  [atom swap-fn then-fn]
  `(let [res# (swap! ~atom ~swap-fn)]
     (~then-fn res#)
     res#))

(defn- fresh-args
  [args]
  (-> args
      (select-keys [:app :opts])
      (assoc :last-args args)))

(defn- finalize-ch
  [{keepalive? :keepalive? last-write :last-write}]
  (when-not last-write
    (throw (Exception. "Somehow the last-write is nil")))
  (when-not keepalive?
    (.addListener last-write netty/close-channel-future-listener)))

(defn- finalize-resp
  [state]
  (swap-then!
   state
   (fn [[current args]]
     (if (= current waiting-for-response)
       [incoming-request (fresh-args args)]
       [current (assoc args :responded? true)]))
   (fn [[next-fn {args :last-args}]]
     (when (= next-fn incoming-request)
       (finalize-ch args))))
  (fn [& args] (throw (Exception. "This response is finished"))))

(defn- stream-or-finalize-resp
  [state ch evt val]
  (cond
   (= :body evt)
   (let [write (.write ch (utils/mk-netty-chunk val))]
     (swap! state (fn [[f args]] [f (assoc args :last-write write)]))
     #(stream-or-finalize-resp state ch %1 %2))

   (= :done evt)
   (finalize-resp state)

   :else
   (throw (Exception. "Unknown event: " evt))))

(defn- is-keepalive?
  [req-keepalive? hdrs]
  (and req-keepalive?
       (or (hdrs "content-length")
           (= (hdrs "transfer-encoding") "chunked"))))

(defn- initialize-resp
  [state ch evt [_ hdrs body :as val]]
  (when-not (= :respond evt)
    (throw (Exception. "Um... responses start with the head?")))
  (let [write (.write ch (utils/resp->netty-resp val))]
    (swap! state
           (fn [[f {keepalive? :keepalive? :as args}]]
             [f (assoc args
                  :keepalive? (is-keepalive? keepalive? hdrs)
                  :last-write write)]))
    (if (= :chunked body)
      #(stream-or-finalize-resp state ch %1 %2)
      (finalize-resp state))))

(defn- downstream-fn
  [state ^Channel ch]
  ;; The state of the response needs to be tracked
  (let [next-fn (atom #(initialize-resp state ch %1 %2))]

    ;; The upstream application will call this function to
    ;; send the response back to the client
    (fn [evt val]
      (cond
       (= evt :pause)
       (.setReadable ch false)

       (= evt :resume)
       (.setReadable ch true)

       :else
       (let [current @next-fn]
         (swap! next-fn (constantly (current evt val)))))
      true)))

(defn- waiting-for-response
  "The HTTP request has been processed and the response is pending.
   In this state, any further downstream messages are unexpected
   since pipelining is not (yet?) supported. Also, if the connection
   does not support keep alives, the connection will get into this state."
  [_ _ _ _]
  (throw (Exception. "Not expecting a message right now")))

(defn- handling-request
  "The initial HTTP request is being handled and we're not expected
   further messages"
  [_ _ _ _]
  (throw (Exception. "Not expecting a message right now")))

(defn- transition-from-req-done
  "State transition from an HTTP request has finished being processed.
   If the response has already been sent, then the next state is to
   listen for new requests."
  [state]
  (swap-then!
   state
   (fn [[_ args]]
     (if (args :responded?)
       [incoming-request (fresh-args args)]
       [waiting-for-response args]))
   (fn [[next-fn {args :last-args :as lol}]]
     (when (= next-fn incoming-request)
       (finalize-ch args)))))

(defn- transition-to-streaming-body
  [state]
  (swap! state (fn [[_ args]] [stream-request-body args])))

(defn- stream-request-body
  [state _ ^HttpChunk chunk {upstream :upstream}]
  (if (.isLast chunk)
    (do
      (upstream :done nil)
      (transition-from-req-done state))
    (upstream :body (.getContent chunk))))

(defn- incoming-request
  [state ch ^HttpMessage msg {app :app :as args}]
  (let [keepalive? (HttpHeaders/isKeepAlive msg)
        upstream   (app (downstream-fn state ch))
        headers    (utils/netty-req->hdrs msg)]

    ;; Add the upstream handler to the state
    (swap! state (fn [[_ args]]
                   [handling-request (assoc args
                                       :keepalive? keepalive?
                                       :upstream   upstream)]))

    ;; Send the HTTP headers upstream
    (if (.isChunked msg)
      (do
        (upstream :request [headers :chunked])
        (transition-to-streaming-body state))
      (do
        (upstream :request
                  [headers
                   (if (headers "content-length")
                     (.getContent msg)
                     nil)])
        (transition-from-req-done state)))))

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
  (let [state     (atom [incoming-request {:app app :opts opts}])
        writable? (atom true)]
    (netty/message-or-channel-state-event-stage
     (fn [^Channel ch msg ch-state]
       (cond
        msg
        (let [[next-fn args] @state]
          (next-fn state ch msg args))

        (and (= ch-state ChannelState/INTEREST_OPS)
             (not= (.isWritable ch) @writable?))
        (if-let [[_ {upstream :upstream}] @state]
          (swap! writable?
                 (fn [was-writable?]
                   (if was-writable?
                     (upstream :pause nil)
                     (upstream :resume nil))
                   (not was-writable?)))))
       nil))))

(defn- create-pipeline
  [app]
  (netty/create-pipeline
   :decoder (HttpRequestDecoder.)
   :encoder (HttpResponseEncoder.)
   :bridge  (netty-bridge app {})))

(defn start
  "Starts an HTTP server on the specified port."
  ([app]
     (start app {:port 4040}))
  ([app opts]
     (netty/start-server #(create-pipeline app) opts)))
