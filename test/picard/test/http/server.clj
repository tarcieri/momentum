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
  [ch1 ch2]
  (start
   (fn [dn]
     (enqueue ch2 [:binding nil])
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

    (is (next-msgs ch2 :binding nil :binding nil :binding nil))

    (is (no-msgs ch1))))

(defcoretest keepalive-head-requests
  [ch1 ch2]
  (start
   (fn [dn]
     (enqueue ch2 [:binding nil])
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [200 {"content-type"   "text/plain"
                             "content-length" "5"} "Hello"])))))

  (with-socket
    (write-socket "HEAD / HTTP/1.1\r\n\r\n")

    (is (next-msgs
         ch1
         :request :dont-care
         :done    nil))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-type: text/plain\r\n"
         "content-length: 5\r\n\r\n"))

    (write-socket "HEAD / HTTP/1.1\r\n\r\n")

    (is (next-msgs
         ch1
         :request :dont-care
         :done    nil))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-type: text/plain\r\n"
         "content-length: 5\r\n\r\n"))

    (write-socket
     "HEAD / HTTP/1.1\r\n"
     "content-type: text/plain\r\n"
     "connection: close\r\n\r\n")

    (is (next-msgs
         ch1
         :request :dont-care
         :done    nil))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-type: text/plain\r\n"
         "content-length: 5\r\n\r\n")))

  (is (next-msgs ch2 :binding nil :binding nil :binding nil)))

(defcoretest keepalive-204-responses
  [ch1 ch2]
  (start
   (fn [dn]
     (enqueue ch2 [:binding nil])
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [204 {} nil])))))

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n\r\n")

    (is (next-msgs ch1 :request :dont-care :done nil))
    (is (receiving "HTTP/1.1 204 No Content\r\n\r\n"))

    (write-socket "POST /blah HTTP/1.1\r\n"
                  "Content-Length: 5\r\n\r\n"
                  "Hello")
    (is (next-msgs ch1 :request [:dont-care "Hello"] :done nil))
    (is (receiving "HTTP/1.1 204 No Content\r\n\r\n"))

    (write-socket "GET /zomg HTTP/1.1\r\n"
                  "lulz: 4-the\r\n\r\n")
    (is (next-msgs ch1 :request :dont-care :done nil))
    (is (receiving "HTTP/1.1 204 No Content\r\n\r\n"))

    (write-socket "GET / HTTP/1.1\r\n"
                  "Connection: close\r\n\r\n")
    (is (next-msgs ch1 :request :dont-care :done nil))
    (is (receiving
         "HTTP/1.1 204 No Content\r\n\r\n"))

    (is (closed-socket?)))

  (is (next-msgs ch2 :binding nil :binding nil :binding nil :binding nil)))

(defcoretest keepalive-304-responses
  [ch1 ch2]
  (start
   (fn [dn]
     (enqueue ch2 [:binding nil])
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [304 {} nil])))))

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n\r\n")

    (is (next-msgs ch1 :request :dont-care :done nil))
    (is (receiving "HTTP/1.1 304 Not Modified\r\n\r\n"))

    (write-socket "POST /blah HTTP/1.1\r\n"
                  "Content-Length: 5\r\n\r\n"
                  "Hello")

    (is (next-msgs ch1 :request [:dont-care "Hello"] :done nil))
    (is (receiving "HTTP/1.1 304 Not Modified\r\n\r\n"))

    (write-socket "GET /zomg HTTP/1.1\r\n"
                  "lulz: 4-the\r\n\r\n")

    (is (next-msgs ch1 :request :dont-care :done nil))
    (is (receiving "HTTP/1.1 304 Not Modified\r\n\r\n"))

    (write-socket "GET / HTTP/1.1\r\n"
                  "Connection: close\r\n\r\n")

    (is (next-msgs ch1 :request :dont-care :done nil))
    (is (receiving "HTTP/1.1 304 Not Modified\r\n\r\n"))

    (is (closed-socket?)))

  (is (next-msgs ch2 :binding nil :binding nil :binding nil :binding nil)))

(defcoretest returning-connection-close-terminates-connection
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [200 {"connection" "close"} "Hello"])))))

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n\r\n")

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "connection: close\r\n\r\n"
         "Hello"))

    (is (closed-socket?))))

(defcoretest returning-connection-close-and-chunks
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [200 {"connection" "close"} :chunked])
         (dn :body "Hello ")
         (dn :body "world")
         (dn :body nil)))))

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n\r\n")

    (is (next-msgs
         ch1
         :request :dont-care
         :done    nil))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "connection: close\r\n\r\n"
         "Hello world"))

    (is (closed-socket?))))

(defcoretest transfer-encoding-chunked-and-keep-alive
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (when (= :request evt)
         (let [[hdrs] val]
           (if (= "GET" (hdrs :request-method))
             (do
               (dn :response [200 {"transfer-encoding" "chunked"} :chunked])
               (dn :body "Hello")
               (dn :body "World")
               (dn :body nil))
             (dn :response [202 {"content-length" "0"}])))))))

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n\r\n")

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "transfer-encoding: chunked\r\n\r\n"
         "5\r\nHello\r\n5\r\nWorld\r\n0\r\n\r\n"))

    (write-socket "POST / HTTP/1.1\r\n"
                  "Connection: close\r\n\r\n")

    (is (receiving
         "HTTP/1.1 202 Accepted\r\n"
         "content-length: 0\r\n\r\n"))

    (is (closed-socket?))))

(defcoretest single-chunked-request
  [ch1]
  (start-hello-world-app ch1)

  (with-socket
    (write-socket "POST / HTTP/1.1\r\n"
                  "Connection: close\r\n"
                  "Transfer-Encoding: chunked\r\n\r\n"
                  "5\r\nHello\r\n5\r\nWorld\r\n0\r\n\r\n")

    (is (next-msgs
         ch1
         :request [#(includes-hdrs {"transfer-encoding" "chunked"} %) :chunked]
         :body    "Hello"
         :body    "World"
         :body    nil
         :done    nil))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-type: text/plain\r\n"
         "content-length: 5\r\n"
         "connection: close\r\n\r\n"
         "Hello"))

    (is (closed-socket?))))

(defcoretest single-chunked-response
  (start
   (fn [dn]
     (fn [evt val]
       (when (= :request evt)
         (dn :response [200 {"transfer-encoding" "chunked"} :chunked])
         (dn :body "Hello")
         (dn :body nil)))))

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n"
                  "Connection: close\r\n\r\n")

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "transfer-encoding: chunked\r\n\r\n"
         "5\r\nHello\r\n0\r\n\r\n"))

    (is (closed-socket?))))

(defcoretest chunked-response-with-content-length
  [ch1 ch2]
  (start
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (try
         (when (= :request evt)
           (dn :response [200 {"content-length" "5"} :chunked])
           (dn :body "Hello")
           (dn :body nil))
         (catch Exception err
           (enqueue ch2 [:error err]))))))

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n\r\n")

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 5\r\n\r\n"
         "Hello"))

    (is (next-msgs
         ch1
         :request :dont-care
         :done    nil))

    (is (no-msgs ch1 ch2))))

(defcoretest chunked-requests-keep-alive
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [200 {"content-length" "5"} "Hello"])))))

  (with-socket
    (write-socket "POST / HTTP/1.1\r\n"
                  "Transfer-Encoding: chunked\r\n\r\n"
                  "5\r\nHello\r\n6\r\n World\r\n0\r\n\r\n")

    (is (next-msgs
         ch1
         :request [#(includes-hdrs {"transfer-encoding" "chunked"} %) :chunked]
         :body    "Hello"
         :body    " World"
         :body    nil
         :done    nil))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 5\r\n\r\n"
         "Hello"))

    (write-socket "POST / HTTP/1.1\r\n"
                  "Transfer-Encoding: chunked\r\n\r\n"
                  "6\r\nZomG!!\r\n9\r\nINCEPTION\r\n0\r\n\r\n")

    (is (next-msgs
         ch1
         :request [#(includes-hdrs {"transfer-encoding" "chunked"} %) :chunked]
         :body    "ZomG!!"
         :body    "INCEPTION"
         :body    nil
         :done    nil))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 5\r\n\r\n"
         "Hello"))

    (write-socket "GET / HTTP/1.1\r\n"
                  "Connection: close\r\n\r\n")

    (is (next-msgs
         ch1
         :request [#(includes-hdrs {"connection" "close"} %) nil]
         :done    nil))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 5\r\n\r\n"
         "Hello"))))
