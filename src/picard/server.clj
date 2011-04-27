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

(defn- stream-or-finalize-resp
  [evt val req-state chan keepalive? streaming? last-write]
  (cond
   (= :body evt)
   (let [write (.write chan (utils/mk-netty-chunk val))]
     #(stream-or-finalize-resp %1 %2
                               req-state chan
                               keepalive? true write))

   (= :done evt)
   (do (if keepalive?
         (swap! req-state (fn [[current args]]
                            (if (= current waiting-for-response)
                              [incoming-request args]
                              [current args true])))
         (.addListener last-write netty/close-channel-future-listener))
       (fn [& args] (throw (Exception. "This request is finished"))))

   :else
   (throw (Exception. "Unknown event: " evt))))

(defn- is-keepalive?
  [req-keepalive? hdrs]
  (and req-keepalive?
       (or (hdrs "content-length")
           (= (hdrs "transfer-encoding") "chunked"))))

(defn- initialize-resp
  [evt [_ hdrs :as val] req-state chan keepalive? streaming? last-write]
  (when-not (= :respond evt)
    (throw (Exception. "Um... responses start with the head?")))
  (let [write (.write chan (utils/resp->netty-resp val))]
    #(stream-or-finalize-resp %1 %2
                              req-state chan
                              (is-keepalive? keepalive? hdrs)
                              streaming? write)))

(defn- downstream-fn
  [state ^Channel ch keepalive?]
  ;; The state of the response needs to be tracked
  (let [next-fn (atom #(initialize-resp
                        %1 %2
                        state ch
                        keepalive? false nil))]

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
         (swap! next-fn (constantly (current evt val)))
         true)))))

(defn- waiting-for-response
  "The HTTP request has been processed and the response is pending.
   In this state, any further downstream messages are unexpected
   since pipelining is not (yet?) supported. Also, if the connection
   does not support keep alives, the connection will get into this state."
  [_ _ _ _]
  (throw (Exception. "Not expecting a message right now")))

(defn- transition-from-req-done
  "State transition from an HTTP request has finished being processed.
   If the response has already been sent, then the next state is to
   listen for new requests."
  [state]
  (swap! state
         (fn [[_ [app opts upstream] responded?]]
           (if responded?
             [incoming-request [app opts]]
             [waiting-for-response [app opts upstream]]))))

(defn- transition-to-streaming-body
  [state]
  (swap! state
         (fn [[_ args responded?]]
           [stream-request-body args responded?])))

(defn- register-upstream-handler
  [state upstream]
  (swap! state
         (fn [[f [app opts] responded?]]
           [f [app opts upstream] responded?])))

(defn- stream-request-body
  [state _ ^HttpChunk chunk [app opts upstream]]
  [[_ _ upstream] _  _ ^HttpChunk chunk]
  (if (.isLast chunk)
    (do
      (upstream :done nil)
      (transition-from-req-done state))
    (upstream :body (.getContent chunk))))

(defn- incoming-request
  [state ch ^HttpMessage msg [app opts]]
  (let [keepalive? (HttpHeaders/isKeepAlive msg)
        upstream   (app (downstream-fn state ch keepalive?))
        headers    (utils/netty-req->hdrs msg)]

    ;; Add the upstream handler to the state
    (register-upstream-handler state upstream)

    ;; Send the HTTP headers upstream
    (if (.isChunked msg)
      (do
        (upstream :request [headers nil])
        (transition-to-streaming-body state))
      (do
        (upstream :request
                  [headers
                   (if (headers "content-length")
                     (.getContent msg)
                     nil)])
        (upstream :done nil)
        (transition-from-req-done state)))))

(defn- get-upstream
  [state]
  (let [[_ [_ _ upstream]] @state] upstream))

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
  (let [state     (atom [incoming-request [app opts]])
        writable? (atom true)]
    (netty/message-or-channel-state-event-stage
     (fn [^Channel ch msg ch-state]
       (cond
        msg
        (let [[next-fn args] @state]
          (next-fn state ch msg args))

        (and (= ch-state ChannelState/INTEREST_OPS)
             (not= (.isWritable ch) @writable?))
        (if-let [upstream (get-upstream state)]
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
