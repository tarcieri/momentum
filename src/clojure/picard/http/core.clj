(ns picard.http.core
  (:require
   [clojure.string         :as str]
   [picard.net.conversions :as conv]
   [picard.net.message     :as msg])
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
    HttpVersion]))

(def http-1-0 [1 0])
(def http-1-1 [1 1])

(extend-protocol msg.NormalizeMessage
  HttpRequest
  (normalize [req]
    (throw (Exception. "Not implemented yet")))
  HttpChunk
  (normalize [chunk]
    [:body (when-not (.isLast chunk) (.getContent chunk))])
  clojure.lang.PersistentVector
  (normalize [msg]
    msg))

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
    (.setContent (conv/to-channel-buffer body))))

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
  (DefaultHttpChunk. (conv/to-channel-buffer chunk)))
