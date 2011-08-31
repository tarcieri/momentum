(ns picard.http.core
  (:require
   [clojure.string     :as str]
   [picard.net.message :as msg])
  (:import
   [org.jboss.netty.buffer
    ChannelBuffer]
   [org.jboss.netty.handler.codec.http
    DefaultHttpChunk
    DefaultHttpResponse
    HttpChunk
    HttpHeaders
    HttpMessage
    HttpMethod
    HttpRequest
    HttpResponse
    HttpResponseStatus
    HttpVersion]
   [java.io
    IOException]
   [java.net
    URI]))

(def http-1-0   [1 0])
(def http-1-1   [1 1])
(def last-chunk HttpChunk/LAST_CHUNK)

(defn throw-connection-reset-by-peer
  []
  (throw (IOException. "Connection reset by peer")))

(defn content-length
  [hdrs]
  (when-let [content-length (hdrs "content-length")]
    (if (number? content-length)
      (long content-length)
      (Long. (str content-length)))))

(defn keepalive-request?
  [[{version :http-version connection "connection"}]]
  (let [connection (and connection (str/lower-case connection))]
    (if (= http-1-1 version)
      (not= "close" connection)
      (= "keep-alive" connection))))

(defn expecting-100?
  [[{version :http-version expect "expect"}]]
  (cond
   (not= http-1-1 version)
   false

   (not expect)
   false

   (string? expect)
   (= "continue" (str/lower-case expect))

   (vector? expect)
   (some #(= "continue" (str/lower-case %)) expect)

   :else
   false))

(defn status-expects-body?
  [status]
  (and (<= 200 status)
       (not= 204 status)
       (not= 304 status)))

(defn- netty-assoc-hdrs
  [^HttpMessage msg hdrs]
  (doseq [[k v-or-vals] hdrs]
    (when (string? k)
      (if (sequential? v-or-vals)
        (doseq [val v-or-vals]
          (when-not (empty? val)
            (.addHeader msg (name k) (str val))))
        (when-not (empty? v-or-vals)
          (.addHeader msg (name k) (str v-or-vals)))))))

(defn- netty-set-content
  [^HttpMessage msg body]
  (if (= :chunked body)
    (.setChunked msg true)
    (.setContent msg (msg/to-channel-buffer body))))

(def netty-versions
  {[1 0] HttpVersion/HTTP_1_0
   [1 1] HttpVersion/HTTP_1_1})

(defn- ^HttpResponse mk-netty-response
  [status {http-version :http-version}]
  (let [version (netty-versions (or http-version [1 1]))
        status  (HttpResponseStatus/valueOf status)]
    (DefaultHttpResponse. version status)))

(defn response->netty-response
  [status hdrs body]
  ;; Stuff
  (doto (mk-netty-response status hdrs)
    (netty-assoc-hdrs hdrs)
    (netty-set-content body)))

(defn chunk->netty-chunk
  [chunk]
  (DefaultHttpChunk. (msg/to-channel-buffer chunk)))

(defn- netty-message->version
  [msg]
  (let [version (.getProtocolVersion msg)]
    [(.getMajorVersion version) (.getMinorVersion version)]))

(defn- merge-header
  [hdrs [k v]]
  (let [k (str/lower-case k)
        existing (hdrs k)]
    (assoc hdrs
      k (cond
         (nil? existing)
         v

         (string? existing)
         [existing v]

         :else
         (conj existing v)))))

(defn- netty-message->headers
  [msg]
  (-> (reduce merge-header {} (.getHeaders msg))
      (assoc :http-version (netty-message->version msg))))

(defn- netty-message->body
  [msg]
  (cond
   (.isChunked msg)
   :chunked

   (.getHeader msg "Content-Length")
   (.getContent msg)))

(defn- netty-request->headers
  [request]
  (let [uri (URI. (.getUri request))]
    (assoc (netty-message->headers request)
      :request-method (.. request getMethod toString)
      :path-info      (.getRawPath uri)
      :query-string   (or (.getRawQuery uri) "")
      :script-name    "")))

(extend-protocol msg/DecodeMessage
  HttpRequest
  (decode [request]
    [:request  [(netty-request->headers request) (netty-message->body request)]])

  HttpChunk
  (decode [chunk]
    [:body (when-not (.isLast chunk) (.getContent chunk))]))

(defprotocol ChunkSize
  (chunk-size [chunk]))

(extend-protocol ChunkSize
  clojure.lang.Keyword
  (chunk-size [_] 0)
  nil
  (chunk-size [_] 0)
  ChannelBuffer
  (chunk-size [c] (.readableBytes c))
  Object
  (chunk-size [c] (count c)))
