(ns picard.utils
  (:require
   [clojure.string :as str]
   [picard])
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
    InetSocketAddress]))

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

;; TODO: Rename this o_O
(defn hard-set!
  "Hard because set! is already in use"
  [atom val]
  (swap! atom (constantly val)))

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
    (-> {}
        (into (map (fn [[k v]] [(str/lower-case k) v])
                   (.getHeaders msg)))
        (assoc :http-version [(.getMajorVersion version)
                              (.getMinorVersion version)]))))

(defn- addr->ip
  [^InetSocketAddress addr]
  [(.. addr getAddress getHostAddress) (.getPort addr)])

(defn netty-req->hdrs
  [^HttpRequest req ^Channel ch]
  (assoc (netty-msg->hdrs req)
    :request-method (.. req getMethod toString)
    :path-info      (.getUri req)
    :script-name    ""
    :server-name    picard/SERVER-NAME
    :local-addr     (addr->ip (.getLocalAddress ch))
    :remote-addr    (addr->ip (.getRemoteAddress ch))))

(defn netty-req->req
  [^HttpMessage req ^Channel ch]
  (let [hdrs (netty-req->hdrs req ch)]
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
     (when (and (string? k)
                (not (nil? v-or-vals)))
       (if (string? v-or-vals)
         (.addHeader msg (name k) v-or-vals)
         (doseq [val v-or-vals]8
           (.addHeader msg (name k) v-or-vals)))))))

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
  (returning
   [netty-req (mk-netty-req
               (HttpMethod. (hdrs :request-method))
               (hdrs :path-info))]
   (netty-assoc-hdrs netty-req hdrs)
   (when body
     (if (= :chunked body)
       (.setChunked netty-req true)
       (.setContent netty-req (*->channel-buffer body))))))

(defn mk-netty-chunk
  [body]
  (DefaultHttpChunk. (*->channel-buffer body)))
