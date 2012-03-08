(ns momentum.http.core
  (:use momentum.core))

(def http-1-0    [1 0])
(def http-1-1    [1 1])
(def SP          (buffer " "))
(def QM          (buffer "?"))
(def CRLF        (buffer "\r\n"))
(def last-chunk  (buffer "0\r\n\r\n"))
(def response-status-reasons
  {100 "100 Continue"
   101 "101 Switching Protocols"
   102 "102 Processing"
   200 "200 OK"
   201 "201 Created"
   202 "202 Accepted"
   203 "203 Non-Authoritative Information"
   204 "204 No Content"
   205 "205 Reset Content"
   206 "206 Partial Content"
   207 "207 Multi-Status"
   226 "226 IM Used"
   300 "300 Multiple Choices"
   301 "301 Moved Permanently"
   302 "302 Found"
   303 "303 See Other"
   304 "304 Not Modified"
   305 "305 Use Proxy"
   306 "306 Reserved"
   307 "307 Temporary Redirect"
   400 "400 Bad Request"
   401 "401 Unauthorized"
   402 "402 Payment Required"
   403 "403 Forbidden"
   404 "404 Not Found"
   405 "405 Method Not Allowed"
   406 "406 Not Acceptable"
   407 "407 Proxy Authentication Required"
   408 "408 Request Timeout"
   409 "409 Conflict"
   410 "410 Gone"
   411 "411 Length Required"
   412 "412 Precondition Failed"
   413 "413 Request Entity Too Large"
   414 "414 Request-URI Too Long"
   415 "415 Unsupported Media Type"
   416 "416 Request Range Not Satisfiable"
   417 "417 Expectation Failed"
   422 "422 Unprocessable Entity"
   423 "423 Locked"
   424 "424 Failed Dependency"
   426 "426 Upgrade Required"
   500 "500 Internal Server Error"
   501 "501 Not Implemented"
   502 "502 Bad Gateway"
   503 "503 Service Unavailable"
   504 "504 Gateway Timeout"
   505 "505 HTTP Version Not Supported"
   506 "506 Variant Also Negotiates"
   507 "507 Insufficient Storage"
   510 "510 Not Extended"})

(def http-version-bytes
  {[1 0] (buffer "HTTP/1.0")
   [1 1] (buffer "HTTP/1.1")})

(defn- hex
  [i]
  (Integer/toHexString i))

(defn http-version-to-bytes
  [v]
  (or (http-version-bytes (or v http-1-1))
      (throw (Exception. (str "Invalid HTTP version: " v)))))

(defn- status-to-reason
  [s]
  (or (response-status-reasons s)
      (throw (Exception. (str "Invalid HTTP status: " s)))))

(defn- write-response-line
  [version status])

(defn- write-request-path
  [buf {path :path-info pfx :script-name qs :query-string}]
  (let [full-path (str (or pfx "") path)]
    (write buf (if (seq full-path) full-path "/"))
    (when (seq qs)
      (write buf QM qs))))

(defn- write-message-header
  [buf name val]
  (when-not (or (nil? val) (= "" val))
    (write buf (str name) ": " (to-string val) CRLF)))

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

(defn encode-response-head
  [status {version :http-version :as hdrs}]
  (let [buf (dynamic-buffer)
        ver (http-version-to-bytes version)
        rsn (status-to-reason status)]
    (write buf ver SP rsn CRLF)
    (write-message-headers buf hdrs)
    (flip buf)))

(defn encode-request-head
  [{method :request-method version :http-version :as hdrs}]
  (let [buf (dynamic-buffer)]
    (write buf method SP)
    (write-request-path buf hdrs)
    (write buf SP (http-version-to-bytes version) CRLF)
    (write-message-headers buf hdrs)
    (flip buf)))

(defn encode-chunk
  [chunk]
  (let [size (hex (remaining chunk))]
    (wrap (buffer size CRLF) chunk CRLF)))