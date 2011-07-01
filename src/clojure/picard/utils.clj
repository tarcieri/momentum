(ns picard.utils
  (:require
   [clojure.string :as str]
   [clojure.contrib.logging :as log]
   [picard.cookie :as cookie])
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
   java.util.UUID
   org.apache.log4j.Logger))

(def VERSION "0.0.1")
(def SERVER-NAME (str "Picard " VERSION " - *FACEPALM*"))

(defmacro debug
  [cmpnt msg]
  (let [logger-name (str "picard.internal." (name cmpnt))]
    (when (System/getProperty "picard.enableDebugging")
      `(log/log :debug ~msg nil ~logger-name))))

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

(defn no-response-body?
  [status]
  (or (> 200 status)
      (= 204 status)
      (= 304 status)))

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
    (-> (netty-msg->hdrs req)
        (assoc
            :request-id     request-id
            :request-method (.. req getMethod toString)
            :path-info      (.getRawPath uri)
            :query-string   (or (.getRawQuery uri) "")
            :script-name    ""
            :server-name    SERVER-NAME
            :local-addr     (addr->ip (.getLocalAddress ch))
            :remote-addr    (addr->ip (.getRemoteAddress ch))))))

(defn netty-req->req
  [^HttpMessage req ^Channel ch request-id]
  (let [hdrs (netty-req->hdrs req ch request-id)
        hdrs (cookie/decode-cookies hdrs "cookie")]
    [hdrs
     (cond
      (.isChunked req)        :chunked
      (hdrs "content-length") (.getContent req)
      :else                   nil)]))

(defn netty-resp->resp
  [^HttpResponse resp head?]
  (let [status (.. resp getStatus getCode)
        body   (when-not (or head?
                             (> 200 status)
                             (= 204 status)
                             (= 304 status))
                 (if (.isChunked resp)
                   :chunked
                   (.getContent resp)))
        hdrs (netty-msg->hdrs resp)
        hdrs (cookie/decode-cookies hdrs "set-cookie")]
    [status hdrs body]))

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

(defn- ^HttpRequest mk-netty-req
  [method uri]
  (when (not (or method uri))
    (throw (Exception. "Need to specify both the HTTP method and URI")))
  (DefaultHttpRequest.
    HttpVersion/HTTP_1_1
    method uri))

(defn resp->netty-resp
  [status hdrs body]
  (let [hdrs (cookie/encode-cookies hdrs "set-cookie" true)]
   (returning [netty-resp ^HttpMessage (mk-netty-response status)]
              (netty-assoc-hdrs netty-resp hdrs)
              (when body
                (if (= :chunked body)
                  (.setChunked netty-resp true)
                  (.setContent netty-resp (*->channel-buffer body)))))))

(defn req->netty-req
  [[hdrs body]]
  (let [hdrs (cookie/encode-cookies hdrs "cookie" false)
        query-string (hdrs :query-string)
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
