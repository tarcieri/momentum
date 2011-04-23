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

(defn- body-from-netty-req
  [^HttpMessage req]
  (.getContent req))

(defn- downstream-fn
  [channel]
  (fn [evt val]
    (when (= evt :done)
      (let [resp (DefaultHttpResponse.
                   HttpVersion/HTTP_1_1 (HttpResponseStatus/valueOf 200))]
        (.setContent resp (formats/string->channel-buffer "Hello world\n"))
        (run-pipeline (.write channel resp)
                      netty/wrap-channel-future
                      (fn [_] (.close channel)))))))

;; Declare some functions in advance -- letfn might be better
(def incoming-request)

(defn- stream-request-body
  [[app opts upstream] _ ^HttpChunk chunk]
  (if (.isLast chunk)
    (do
      (upstream :done nil)
      #(incoming-request [app opts] %1 %2))
    (do
      (upstream :body (.getContent chunk))
      #(stream-request-body [app opts upstream] %1 %2))))

(defn- incoming-request
  [[app opts] channel ^HttpMessage msg]
  (let [upstream (app (downstream-fn channel))]
    ;; Send the HTTP headers upstream
    (upstream :headers (headers-from-netty-req msg))

    (if (.isChunked msg)
      #(stream-request-body [app opts upstream] %1 %2)
      (do
        (upstream :body (.getContent msg))
        (upstream :done nil)
        #(incoming-request [app opts] %1 %2)))))

(defn- netty-bridge
  [app opts]
  (let [evt-handler (atom #(incoming-request [app opts] %1 %2))]
    (netty/message-stage
     (fn [ch msg]
       (let [next-fn @evt-handler]
         (if-not (compare-and-set! evt-handler next-fn (next-fn ch msg))
           (throw (Exception. "COME ON!"))
           nil))))))

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
