(ns picard.utils
  (:require
   [clojure.string :as str]
   [clojure.contrib.logging :as log])
  (:import
   [java.nio
    ByteBuffer]
   [org.jboss.netty.channel
    Channel]
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
    HttpVersion]
   [java.net
    InetAddress
    InetSocketAddress
    URI]
   [java.util
    UUID]))

(def VERSION "0.0.1")
(def SERVER-NAME (str "Picard " VERSION " - *FACEPALM*"))

(defmacro debug
  [cmpnt msg]
  `(log/log :debug ~msg nil ~(str "picard.internal." (name cmpnt))))

(defmacro when-debug
  [cmpnt & stmts]
  (when (log/enabled? :debug (str "picard.internal." (name cmpnt)))
    `(do ~@stmts)))

(defmacro returning
  [val & stmts]
  (if (vector? val)
    (let [[retval form] val]
      `(let [~retval ~form]
         ~@stmts
         ~retval))
    `(do ~@stmts ~val)))

(defmacro with
  [val _ name & stmts]
  `(let [~name ~val]
     ~@stmts))

(defmacro cond-let
  ([] nil)
  ([binding clause & rest]
     (if (= :else binding)
       clause
       `(if-let ~binding
          ~clause
          (cond-let ~@rest)))))

(defmacro swap-then!
  [atom swap-fn then-fn]
  `(let [res# (swap! ~atom ~swap-fn)]
     (~then-fn res#)
     res#))

(defn gen-uuid
  []
  (.toString ^UUID (UUID/randomUUID)))

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
  (cond
   (instance? ChannelBuffer thing)
   thing

   :else
   (string->channel-buffer thing)))

(defn netty-msg->hdrs
  [^HttpMessage msg]
  (let [version ^HttpVersion (.getProtocolVersion msg)]
    (-> (reduce
         (fn [hdrs [name val]]
           (let [name (str/lower-case name)]
             (assoc hdrs
               name (if-let [existing (hdrs name)]
                      (conj (if (vector? existing) existing [existing]) val)
                      val))))
         {} (.getHeaders msg))
        (assoc :http-version [(.getMajorVersion version)
                              (.getMinorVersion version)]))))

(defn- addr->ip
  [^InetSocketAddress addr]
  [(.. addr getAddress getHostAddress) (.getPort addr)])

(defn netty-req->hdrs
  [^HttpRequest req ^Channel ch request-id]
  (let [uri (URI. (.getUri req))]
    (assoc (netty-msg->hdrs req)
      :request-id     request-id
      :request-method (.. req getMethod toString)
      :path-info      (.getRawPath uri)
      :query-string   (or (.getRawQuery uri) "")
      :script-name    ""
      :server-name    SERVER-NAME
      :local-addr     (addr->ip (.getLocalAddress ch))
      :remote-addr    (addr->ip (.getRemoteAddress ch)))))

(defn netty-req->req
  [^HttpMessage req ^Channel ch request-id]
  (let [hdrs (netty-req->hdrs req ch request-id)]
    [hdrs
     (cond
      (.isChunked req)        :chunked
      (hdrs "content-length") (.getContent req)
      :else                   nil)]))

(defn netty-resp->resp
  [^HttpResponse resp]
  [(.. resp getStatus getCode)
   (netty-msg->hdrs resp)
   (if (.isChunked resp)
    :chunked
    (.getContent resp))])

(defn netty-msg->body
  [^HttpMessage msg]
  (.getContent msg))

(defn- netty-assoc-hdrs
  [^HttpMessage msg hdrs]
  (returning
   msg
   (doseq [[k v-or-vals] hdrs]
     (when (string? k)
       (if (sequential? v-or-vals)
         (doseq [val v-or-vals]
           (when-not (empty? val)
             (.addHeader msg (name k) (str val))))
         (when-not (or (nil? v-or-vals) (= "" v-or-vals))
           (.addHeader msg (name k) (str v-or-vals))))))))

(defn- mk-netty-response
  [status]
  (DefaultHttpResponse. HttpVersion/HTTP_1_1
    (HttpResponseStatus/valueOf status)))

(defn- mk-netty-req
  [method uri]
  (when (not (or method uri))
    (throw (Exception. "Need to specify both the HTTP method and URI")))
  (DefaultHttpRequest.
    HttpVersion/HTTP_1_1
    method uri))

(defn resp->netty-resp
  [[status hdrs body]]
  (returning [netty-resp ^HttpMessage (mk-netty-response status)]
             (netty-assoc-hdrs netty-resp hdrs)
             (when body
               (if (= :chunked body)
                 (.setChunked netty-resp true)
                 (.setContent netty-resp (*->channel-buffer body))))))

(defn req->netty-req
  [[hdrs body]]
  (let [query-string (hdrs :query-string)
        path-info (hdrs :path-info)
        netty-req (mk-netty-req
                   (HttpMethod. (hdrs :request-method))
                   (if (empty? query-string)
                     path-info
                     (str path-info "?" query-string)))]
    (netty-assoc-hdrs netty-req hdrs)
    (when body
      (if (= :chunked body)
        (.setChunked netty-req true)
        (.setContent netty-req (*->channel-buffer body))))
    netty-req))

(defn mk-netty-chunk
  [body]
  (DefaultHttpChunk. (*->channel-buffer body)))
