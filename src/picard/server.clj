(ns picard.server
  (:require
   [clojure.string :as str]
   [picard.netty :as netty]
   [picard.formats :as formats])
  (:use
   [lamina.core])
  (:import
   [org.jboss.netty.channel
    Channel]
   [org.jboss.netty.handler.codec.http
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

(defn- headers-from-netty-req
  [^HttpMessage req]
  (into {} (map
            (fn [[k v]] [(str/lower-case k) v])
            (.getHeaders req))))

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

(defn- body-from-netty-req
  [^HttpMessage req]
  (.getContent req))

;; Declare some functions in advance -- letfn might be better
(def incoming-request)
(def stream-request-body)
(def waiting-for-response)

(defn- stream-or-finalize-resp
  [evt val req-state chan keepalive? streaming? last-write]
  (when (= :body evt)
    (throw (Exception. "Not implemented yet...")))

  (if keepalive?
    (swap! req-state (fn [[current args]]
                       (if (= current waiting-for-response)
                         [incoming-request args]
                         [current args true])))
    (.addListener (last-write netty/close-channel-future-listener))))

(defn- initialize-resp
  [evt val req-state chan keepalive? streaming? last-write]
  (when-not (= :respond evt)
    (throw (Exception. "Um... responses start with the head?")))
  (let [write (.write chan (resp-to-netty-resp val))]
    #(stream-or-finalize-resp %1 %2
                              req-state chan
                              keepalive? streaming? write)))

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
  (println "BLOWING UP")
  (throw (Exception. "Not expecting a message right now")))

(defn- transition-from-req-done
  "State transition from an HTTP request has finished being processed.
   If the response has already been sent, then the next state is to
   listen for new requests."
  [[_ args responded?]]
  (if responded?
    [incoming-request args]
    [waiting-for-response args]))

(defn- transition-to-streaming-body
  [upstream [_ [app opts] responded?]]
  [stream-request-body [app opts upstream] responded?])

(defn- stream-request-body
  [[app opts upstream] _  _ ^HttpChunk chunk]
  (if (.isLast chunk)
    (do
      (upstream :done nil)
      transition-from-req-done)
    (do
      (upstream :body (.getContent chunk))
      (partial transition-to-streaming-body upstream))))

(defn- incoming-request
  [[app opts] state channel ^HttpMessage msg]
  (let [upstream (app (downstream-fn state channel (HttpHeaders/isKeepAlive msg)))]
    ;; Send the HTTP headers upstream
    (upstream :headers (headers-from-netty-req msg))

    (if (.isChunked msg)
      (partial transition-to-streaming-body upstream)
      (do
        (upstream :body (.getContent msg))
        (upstream :done nil)
        transition-from-req-done))))

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
    (netty/message-stage
     (fn [ch msg]
        (let [[next-fn args] @state]
          (swap! state (next-fn args state ch msg))
          nil)))))

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
