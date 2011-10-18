(ns picard.http.core
  (:use
   picard.core.buffer
   picard.http.parser)
  (:require
   [clojure.string     :as str]
   [picard.net.message :as msg])
  (:import
   [picard.core
    Buffer]
   [org.jboss.netty.buffer
    ChannelBuffer
    ChannelBuffers]
   [org.jboss.netty.handler.codec.http
    DefaultHttpChunk
    DefaultHttpRequest
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

(def SP          (buffer " "))
(def CRLF        (buffer "\r\n"))
(def http-1-0    [1 0])
(def http-1-1    [1 1])
(def last-chunk  HttpChunk/LAST_CHUNK)
(def last-chunk* (buffer "0\r\n\r\n"))

(def response-status-reasons
  {100 "Continue"
   101 "Switching Protocols"
   102 "Processing"
   200 "OK"
   201 "Created"
   202 "Accepted"
   203 "Non-Authoritative Information"
   204 "No Content"
   205 "Reset Content"
   206 "Partial Content"
   207 "Multi-Status"
   226 "IM Used"
   300 "Multiple Choices"
   301 "Moved Permanently"
   302 "Found"
   303 "See Other"
   304 "Not Modified"
   305 "Use Proxy"
   306 "Reserved"
   307 "Temporary Redirect"
   400 "Bad Request"
   401 "Unauthorized"
   402 "Payment Required"
   403 "Forbidden"
   404 "Not Found"
   405 "Method Not Allowed"
   406 "Not Acceptable"
   407 "Proxy Authentication Required"
   408 "Request Timeout"
   409 "Conflict"
   410 "Gone"
   411 "Length Required"
   412 "Precondition Failed"
   413 "Request Entity Too Large"
   414 "Request-URI Too Long"
   415 "Unsupported Media Type"
   416 "Request Range Not Satisfiable"
   417 "Expectation Failed"
   422 "Unprocessable Entity"
   423 "Locked"
   424 "Failed Dependency"
   426 "Upgrade Required"
   500 "Internal Server Error"
   501 "Not Implemented"
   502 "Bad Gateway"
   503 "Service Unavailable"
   504 "Gateway Timeout"
   505 "HTTP Version Not Supported"
   506 "Variant Also Negotiates"
   507 "Insufficient Storage"
   510 "Not Extended"})

(defn- maybe-lower-case
  [s]
  (and s (str/lower-case s)))

(defn- hex
  [i]
  (Integer/toHexString i))

(defn throw-connection-reset-by-peer
  []
  (throw (IOException. "Connection reset by peer")))

(defn status-expects-body?
  [status]
  (and (<= 200 status)
       (not= 204 status)
       (not= 304 status)))

(defn content-length
  [hdrs]
  (when-let [content-length (hdrs "content-length")]
    (if (number? content-length)
      (long content-length)
      (Long. (str content-length)))))

(defn keepalive-request?
  [[{version :http-version connection "connection"} body]]
  (let [connection (maybe-lower-case connection)]
    (if (or (nil? version) (= http-1-1 version))
      (and (not (#{"close" "upgrade"} connection))
           (not (= :upgraded body)))
      (= "keep-alive" connection))))

(defn keepalive-response?
  ([request] (keepalive-response? request false))
  ([[status {version           :http-version
             connection        "connection"
             content-length    "content-length"
             transfer-encoding "transfer-encoding"}] head?]
     (let [connection (maybe-lower-case connection)
           version    (or version http-1-1)]
       (and
        (if (= http-1-1 version)
          (not= "close" connection)
          (= "keep-alive" connection))
        (or head?
            content-length
            (= "chunked" (maybe-lower-case transfer-encoding))
            (not (status-expects-body? status)))))))

(defn is-100?
  [[status]]
  (= 100 status))

(defn expecting-100?
  [[{version :http-version expect "expect"}]]
  (cond
   (not= http-1-1 version)
   false

   (not expect)
   false

   (string? expect)
   (= "100-continue" (str/lower-case expect))

   (vector? expect)
   (some #(= "100-continue" (str/lower-case %)) expect)

   :else
   false))

(defn request-parser
  "Wraps an upstream function with the basic HTTP parser."
  [f]
  (let [p (parser f)]
    (fn [evt val]
      (if (= :message evt)
        (p val)
        (f evt val)))))

;; Converting HTTP messages to buffers

(def http-version-bytes
  {http-1-0 (buffer "HTTP/1.0")
   http-1-1 (buffer "HTTP/1.1")})

(defn- http-version-to-bytes
  [v]
  (or (http-version-bytes (or v http-1-1))
      (throw (Exception. (str "Invalid HTTP version: " v)))))

(defn- status-to-reason
  [s]
  (or (response-status-reasons s)
      (throw (Exception. (str "Invalid HTTP status: " s)))))

(defn- write-message-header
  [buf name val]
  (when-not (or (nil? val) (= "" val))
    (write buf (str name) ": " (str val) CRLF)))

(defn- write-message-headers
  [buf hdrs]
  (doseq [[name v-or-vals] hdrs]
    (when (string? name)
      (if (sequential? v-or-vals)
        (doseq [val v-or-vals]
          (write-message-header buf name val))
        (write-message-header buf name v-or-vals))))

  ;; Send the final CRLF
  (write buf CRLF))

(defn send-response
  [dn status {version :http-version :as hdrs} body]
  (let [buf (dynamic-buffer)
        ver (http-version-to-bytes version)
        rsn (status-to-reason status)]
    (write buf ver SP (str status) SP rsn CRLF)
    (write-message-headers buf hdrs)
    (dn :message (flip buf)))

  (when (and body (not (keyword? body)))
    (dn :message body)))

(defn send-chunk
  [dn chunked? chunk]
  (let [chunk (buffer chunk)]
    (cond
     (and chunked? chunk)
     (let [size (hex (remaining chunk))]
       (dn :message (wrap (buffer size CRLF) chunk CRLF)))

     chunked?
     (dn :message last-chunk*)

     chunk
     (dn :message chunk))))

;; ==== Most of the code below is deprecated

(defn- netty-assoc-hdrs
  [^HttpMessage msg hdrs]
  (doseq [[k v-or-vals] hdrs]
    (when (string? k)
      (if (sequential? v-or-vals)
        (doseq [val v-or-vals]
          (when-not (empty? val)
            (.addHeader msg (name k) (str val))))
        (when-not (or (nil? v-or-vals) (= "" v-or-vals))
          (.addHeader msg (name k) (str v-or-vals)))))))

(defn- netty-set-content
  [^HttpMessage msg body]
  (cond
   (= :chunked body)
   (.setChunked msg true)

   body
   (.setContent msg (to-channel-buffer body))))

(def netty-versions
  {[1 0] HttpVersion/HTTP_1_0
   [1 1] HttpVersion/HTTP_1_1})

(defn- headers->uri
  [{query-string :query-string path-info :path-info}]
  (if (empty? query-string)
    path-info
    (str path-info "?" query-string)))

(defn- ^HttpRequest mk-netty-request
  [{http-version :http-version request-method :request-method :as headers}]
  (let [version (netty-versions (or http-version [1 1]))
        method  (HttpMethod. request-method)
        uri     (headers->uri headers)]
    (DefaultHttpRequest. version method uri)))

(defn- ^HttpResponse mk-netty-response
  [status {http-version :http-version}]
  (let [version (netty-versions (or http-version [1 1]))
        status  (HttpResponseStatus/valueOf status)]
    (DefaultHttpResponse. version status)))

(defn request->netty-request
  [hdrs body]
  (doto (mk-netty-request hdrs)
    (netty-assoc-hdrs hdrs)
    (netty-set-content body)))

(defn response->netty-response
  [status hdrs body]
  (doto (mk-netty-response status hdrs)
    (netty-assoc-hdrs hdrs)
    (netty-set-content body)))

(defn chunk->netty-chunk
  [chunk]
  (DefaultHttpChunk. (to-channel-buffer chunk)))

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

(defn- netty-response->status
  [response]
  (.. response getStatus getCode))

(defn- netty-response->headers
  [response]
  (netty-message->headers response))

(defn- netty-response->body
  [response]
  (let [status (netty-response->status response)]
    (when (status-expects-body? status)
      (netty-message->body response))))

(extend-protocol msg/DecodeMessage
  HttpRequest
  (decode [request]
    (let [headers (netty-request->headers request)
          body    (netty-message->body request)]
     [:request  [headers body]]))

  HttpResponse
  (decode [response]
    (let [status  (netty-response->status response)
          headers (netty-response->headers response)
          body    (netty-response->body response)]
      [:response [status headers body]]))

  HttpChunk
  (decode [chunk]
    [:body (when-not (.isLast chunk) (.getContent chunk))]))

(defprotocol ChunkSize
  (chunk-size [_]))

(extend-protocol ChunkSize
  clojure.lang.Keyword
  (chunk-size [_] 0)

  nil
  (chunk-size [_] 0)

  Buffer
  (chunk-size [buf]
    (remaining buf))

  Object
  (chunk-size [o]
    (throw (Exception. (str "Not a valid body chunk type: " o)))))
