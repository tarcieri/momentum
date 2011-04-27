(ns picard.utils
  (:require
   [clojure.string :as str]
   [picard])
  (:import
   [java.nio
    ByteBuffer]
   [org.jboss.netty.buffer
    ChannelBuffer
    ChannelBuffers]
   [org.jboss.netty.handler.codec.http
    DefaultHttpChunk
    DefaultHttpRequest
    DefaultHttpResponse
    HttpMessage
    HttpMethod
    HttpRequest
    HttpResponse
    HttpResponseStatus
    HttpVersion]))

(defmacro returning
  [val & stmts]
  (if (vector? val)
    (let [[retval form] val]
      `(let [~retval ~form]
         ~@stmts
         ~retval))
    `(do ~@stmts ~val)))

(defn string->byte-buffer
  ([s] (string->byte-buffer s "UTF-8"))
  ([s charset]
     (when s
       (ByteBuffer/wrap (.getBytes ^String s ^String charset)))))

(defn string->channel-buffer
  ([s]
     (string->channel-buffer s "UTF-8"))
  ([s charset]
     (when s
       (ChannelBuffers/wrappedBuffer
        (.getBytes ^String s ^String charset)))))

(defn *->channel-buffer
  "Obviously not fully implemented yet"
  [thing]
  (string->channel-buffer thing))

(defn netty-msg->hdrs
  [^HttpMessage req]
  (into {} (map
            (fn [[k v]] [(str/lower-case k) v])
            (.getHeaders req))))

(defn netty-req->hdrs
  [^HttpRequest req]
  (assoc (netty-msg->hdrs req)
    :request-method (.. req getMethod toString)
    :path-info      (.getUri req)
    :script-name    ""
    :server-name    picard/SERVER-NAME))

(defn netty-msg->body
  [^HttpMessage msg]
  (.getContent msg))

(defn- netty-assoc-hdrs
  [^HttpMessage msg hdrs]
  (returning
   msg
   (doseq [[k v-or-vals] hdrs]
     (when (and (string? k)
                (not (nil? v-or-vals)))
       (if (string? v-or-vals)
         (.addHeader msg (name k) v-or-vals)
         (doseq [val v-or-vals]
           (.addHeader msg (name k) v-or-vals)))))))

(defn- mk-netty-response
  [status]
  (DefaultHttpResponse. HttpVersion/HTTP_1_1
    (HttpResponseStatus/valueOf status)))

(defn- mk-netty-req
  [method path]
  (DefaultHttpRequest.
    HttpVersion/HTTP_1_1
    method path))

(defn resp->netty-resp
  [[status hdrs body]]
  (returning [netty-resp (mk-netty-response status)]
             (netty-assoc-hdrs netty-resp hdrs)
             (when body
               (.setContent netty-resp (*->channel-buffer body)))))

(defn req->netty-req
  [[hdrs body]]
  (returning
   [netty-req (mk-netty-req
               (HttpMethod. (hdrs :request-method))
               (hdrs :path-info))]
   (netty-assoc-hdrs netty-req hdrs)
   (when body
     (.setContent netty-req (*->channel-buffer body)))))

(defn mk-netty-chunk
  [body]
  (DefaultHttpChunk. (*->channel-buffer body)))
