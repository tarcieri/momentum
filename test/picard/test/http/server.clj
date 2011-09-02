(ns picard.test.http.server
  (:use
   clojure.test
   support.helpers
   picard.http.server))

(defn- start-hello-world-app
  [ch]
  (start
   (fn [dn]
     (fn [evt val]
       (enqueue ch [evt val])
       (when (= :request evt)
         (dn :response [200 {"content-type"   "text/plain"
                             "content-length" "5"
                             "connection"     "close"} "Hello"]))))))

(defcoretest simple-requests
  [ch1]
  (start-hello-world-app ch1)

  (doseq [method ["GET" "POST" "PUT" "DELETE"]]
    (with-socket
      (write-socket method " / HTTP/1.1\r\n\r\n")

      (is (next-msgs
           ch1
           :request [{:local-addr     ["127.0.0.1" 4040]
                      :remote-addr    ["127.0.0.1" :dont-care]
                      :script-name    ""
                      :path-info      "/"
                      :query-string   ""
                      :request-method method
                      :http-version   [1 1]} nil]
           :done nil))

      (is (no-msgs ch1))

      (is (receiving
           "HTTP/1.1 200 OK\r\n"
           "content-type: text/plain\r\n"
           "content-length: 5\r\n"
           "connection: close\r\n\r\n"
           "Hello"))

      (is (closed-socket?)))))

(defcoretest request-with-query-string
  [ch1]
  (start-hello-world-app ch1)

  (with-socket
    (write-socket "GET /foo?bar=baz HTTP/1.1\r\n\r\n")

    (is (next-msgs
         ch1
         :request [{:local-addr     ["127.0.0.1" 4040]
                    :remote-addr    ["127.0.0.1" :dont-care]
                    :script-name    ""
                    :path-info      "/foo"
                    :query-string   "bar=baz"
                    :request-method "GET"
                    :http-version   [1 1]} nil]
         :done nil))

    (is (no-msgs ch1))))

(defcoretest simple-request-with-body
  [ch1]
  (start-hello-world-app ch1)

  (with-socket
    (write-socket "POST / HTTP/1.1\r\n"
                  "Connection: close\r\n"
                  "Content-Length: 5\r\n\r\n"
                  "Hello")

    (is (next-msgs
         ch1
         :request [#(includes-hdrs {"content-length" "5"} %) "Hello"]
         :done    nil))

    (is (no-msgs ch1))))

(defcoretest request-with-full-uri
  [ch1]
  (start-hello-world-app ch1)

  (with-socket
    (write-socket "GET http://www.google.com/search?q=zomg HTTP/1.1\r\n\r\n")

    (is (next-msgs
         ch1
         :request [{:local-addr     ["127.0.0.1" 4040]
                    :remote-addr    ["127.0.0.1" :dont-care]
                    :script-name    ""
                    :path-info      "/search"
                    :query-string   "q=zomg"
                    :request-method "GET"
                    :http-version   [1 1]} nil]))
    (is (receiving "HTTP/1.1 200 OK\r\n"))))

(defcoretest non-string-response-headers
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (when (= :request evt)
         (dn :response [200 {"content-length" 5
                             "connection" "close"} "Hello"])))))

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n\r\n")

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 5\r\n"
         "connection: close\r\n\r\n"
         "Hello"))

    (is (closed-socket?))))

(defcoretest honors-http-1-0-responses
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (when (= :request evt)
         (dn :response [200 {:http-version [1 0]} "Hello"])))))

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n\r\n")

    (is (receiving
         "HTTP/1.0 200 OK\r\n\r\n"
         "Hello"))

    (is (closed-socket?))))

(defcoretest head-request-with-content-length
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (when (= :request evt)
         (let [[hdrs] val]
           (dn :response [200 {"content-length" "10"} nil]))))))

  (with-socket
    (write-socket "HEAD / HTTP/1.1\r\n"
                  "Connection: close\r\n\r\n")

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 10\r\n\r\n"))

    (is (closed-socket?))))

(defcoretest head-request-with-content-length-and-response
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (when (= :request evt)
         (let [[hdrs] val]
           (dn :response [200 {"content-length" "5"} "Hello"]))))))

  (with-socket
    (write-socket "HEAD / HTTP/1.1\r\n"
                  "Connection: close\r\n\r\n")

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 5\r\n\r\n"))

    (is (closed-socket?))))

(defcoretest head-request-with-te-chunked-and-response-body
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (when (= :request evt)
         (dn :response [200 {"transfer-encoding" "chunked"} :chunked])
         (dn :body "Hello")
         (dn :body nil)))))

  (with-socket
    (write-socket "HEAD / HTTP/1.1\r\n\r\n")

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "transfer-encoding: chunked\r\n\r\n"))

    (write-socket "HEAD / HTTP/1.1\r\n"
                  "connection: close\r\n\r\n")

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "transfer-encoding: chunked\r\n\r\n"))

    (is (closed-socket?))))

(defcoretest no-content-response-but-with-content
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (let [[hdrs] val]
           (try
             (dn :response [204 {"content-length" "5"} "Hello"])
             (catch Exception _
               (dn :response [200 {"content-length" "4"} "ZOMG"]))))))))

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n"
                  "Connection: close\r\n\r\n")

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 4\r\n\r\n"
         "ZOMG"))

    (is (closed-socket?))))

(defcoretest not-modified-response-but-with-content
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (let [[hdrs] val]
           (try
             (dn :response [304 {"content-length" "5"} "Hello"])
             (catch Exception _
               (dn :response [200 {"content-length" "4"} "ZOMG"]))))))))

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n"
                  "Connection: close\r\n\r\n")

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 4\r\n\r\n"
         "ZOMG"))))

(defcoretest simple-http-1-0-request
  [ch1]
  (start-hello-world-app ch1)

  (with-socket
    (write-socket "GET / HTTP/1.0\r\n\r\n")

    (is (next-msgs
         ch1
         :request [{:script-name    ""
                    :path-info      "/"
                    :query-string   ""
                    :request-method "GET"
                    :remote-addr    :dont-care
                    :local-addr     :dont-care
                    :http-version   [1 0]} nil]
         :done nil))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"))))

(defcoretest request-and-response-with-duplicated-headers
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response
             [200 {"content-length" "0"
                   "connection"     "close"
                   "foo"            "lol"
                   "bar"            ["omg" "hi2u"]
                   "baz"            ["1" "2" "3"]} ""])))))

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n"
                  "baz: lol\r\n"
                  "bar: omg\r\n"
                  "bar: hi2u\r\n"
                  "foo: 1\r\n"
                  "foo: 2\r\n"
                  "foo: 3\r\n\r\n")

    (is (next-msgs
         ch1
         :request [{:script-name    ""
                    :path-info      "/"
                    :query-string   ""
                    :request-method "GET"
                    :remote-addr    :dont-care
                    :local-addr     :dont-care
                    :http-version   [1 1]
                    "foo"           ["1" "2" "3"]
                    "bar"           ["omg" "hi2u"]
                    "baz"           "lol"} nil]
         :done nil))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 0\r\n"
         "connection: close\r\n"
         "foo: lol\r\n"
         "bar: omg\r\n"
         "bar: hi2u\r\n"
         "baz: 1\r\n"
         "baz: 2\r\n"
         "baz: 3\r\n\r\n"))))

(defcoretest keepalive-requests
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [200 {"content-length" "5"} "Hello"])))))

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n\r\n")

    (is (next-msgs
         ch1
         :request [#(includes-hdrs {:request-method "GET" :path-info "/"} %) nil]
         :done    nil))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 5\r\n\r\n"
         "Hello"))

    (write-socket "GET /foo HTTP/1.1\r\n\r\n")

    (is (next-msgs
         ch1
         :request [#(includes-hdrs {:request-method "GET" :path-info "/foo"} %) nil]
         :done    nil))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 5\r\n\r\n"
         "Hello"))

    (write-socket "POST /bar HTTP/1.1\r\n\r\n")

    (is (next-msgs
         ch1
         :request [#(includes-hdrs {:request-method "POST" :path-info "/bar"} %) nil]
         :done    nil))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 5\r\n\r\n"
         "Hello"))

    (close-socket)

    (is (no-msgs ch1))))
