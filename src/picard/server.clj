(ns picard.server
  (:require
   [clojure.string :as str]
   [picard]
   [picard.netty :as netty]
   [picard.formats :as formats])
  (:import
   [org.jboss.netty.channel
    Channel
    ChannelEvent
    ChannelState
    ChannelStateEvent
    MessageEvent]
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

(def SERVER-NAME (str "Picard " picard/VERSION " - *FACEPALM*"))

(defn- headers-from-netty-req
  [^HttpMessage req]
  (-> {}
      (into (map
             (fn [[k v]] [(str/lower-case k) v])
             (.getHeaders req)))
      (assoc :request-method (.. req getMethod toString)
             :path-info (.getUri req)
             :script-name ""
             :server-name SERVER-NAME)))

(defn- resp-to-netty-resp
  [[status hdrs body]]
  (let [resp (DefaultHttpResponse.
               HttpVersion/HTTP_1_1 (HttpResponseStatus/valueOf status))]
    (doseq [[k v-or-vals] hdrs]
      (when-not (nil? v-or-vals)
        (if (string? v-or-vals)
          (.addHeader resp (name k) v-or-vals)
          (doseq [val v-or-vals]
            (.addHeader resp (name k) v-or-vals)))))
    (when body
      (.setContent resp (formats/string->channel-buffer body)))
    resp))

(defn- body-to-netty-chunk
  [body]
  (DefaultHttpChunk. (formats/string->channel-buffer body)))

(defn- body-from-netty-req
  [^HttpMessage req]
  (.getContent req))

;; Declare some functions in advance -- letfn might be better
(declare incoming-request stream-request-body waiting-for-response)

(defn- stream-or-finalize-resp
  [evt val req-state chan keepalive? streaming? last-write]
  (cond
   (= :body evt)
   (let [write (.write chan (body-to-netty-chunk val))]
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
  (let [write (.write chan (resp-to-netty-resp val))]
    #(stream-or-finalize-resp %1 %2
                              req-state chan
                              (is-keepalive? keepalive? hdrs)
                              streaming? write)))

(defn- downstream-fn
  [state channel keepalive?]
  ;; The state of the response needs to be tracked
  (let [next-fn (atom #(initialize-resp %1 %2
                                        state channel
                                        keepalive? false nil))]

    ;; The upstream application will call this function to
    ;; send the response back to the client
    (fn [evt val]
      (let [current @next-fn]
        (swap! next-fn (constantly (current evt val)))
        true))))

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
  [upstream [_ [app opts] responded?]]
  (if responded?
    [incoming-request [app opts]]
    [waiting-for-response [app opts upstream]]))

(defn- transition-to-streaming-body
  [upstream [_ [app opts] responded?]]
  [stream-request-body [app opts upstream] responded?])

(defn- stream-request-body
  [[app opts upstream] _  _ ^HttpChunk chunk]
  (if (.isLast chunk)
    (do
      (upstream :done nil)
      (partial transition-from-req-done upstream))
    (do
      (upstream :body (.getContent chunk))
      (partial transition-to-streaming-body upstream))))

(defn- incoming-request
  [[app opts] state channel ^HttpMessage msg]
  (let [upstream (app (downstream-fn state channel (HttpHeaders/isKeepAlive msg)))
        headers  (headers-from-netty-req msg)]
    ;; HACK - Set the state to include the upstream
    (swap! state (fn [[f]] [f [app opts upstream]]))

    ;; Send the HTTP headers upstream
    (if (.isChunked msg)
      (do
        (upstream :request [headers nil])
        (partial transition-to-streaming-body upstream))
      (do
        (upstream :request
                  [headers
                   (if (headers "content-length")
                     (.getContent msg)
                     nil)])
        (upstream :done nil)
        (partial transition-from-req-done upstream)))))

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
  (let [state (atom [incoming-request [app opts]])]
    (netty/upstream-stage
     (fn [^ChannelEvent evt]
       (let [ch ^Channel (.getChannel evt)]
         (cond
          ;; If we got a message, then we need to
          ;; run it through the state machine
          (instance? MessageEvent evt)
          (let [msg (.getMessage ^MessageEvent evt)
                [next-fn args] @state]
            (swap! state (next-fn args state ch msg)))

          (and (instance? ChannelStateEvent evt)
               (= ChannelState/INTEREST_OPS
                  ^ChannelState (.getState ^ChannelStateEvent evt)))
          (let [[_ [_ _ upstream]] @state]
            (when upstream
             (if (.isWritable ch)
               (upstream :resume nil)
               (upstream :pause nil))))))
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
