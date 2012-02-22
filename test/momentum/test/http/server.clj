(ns momentum.test.http.server
  (:use
   clojure.test
   support.helpers
   momentum.core
   momentum.http.server))

;; TODO:
;; - Basic chunked body request / responses

(defn- start-hello-world-app
  [ch]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch [evt val])
       (when (= :request evt)
         (dn :response [200 {"content-type"   "text/plain"
                             "content-length" "5"
                             "connection"     "close"} (buffer "Hello")]))))
   {:pipeline false}))

(defcoretest simple-requests
  [ch1]
  (start-hello-world-app ch1)

  (doseq [method ["GET" "POST" "PUT" "DELETE"]]
    (with-socket
      (write-socket method " / HTTP/1.1\r\n\r\n")

      (is (next-msgs
           ch1
           :open    :dont-care
           :request [{:script-name    ""
                      :path-info      "/"
                      :query-string   ""
                      :request-method method
                      :http-version   [1 1]} nil]
           :close nil))

      (is (no-msgs ch1))

      (is (receiving
           "HTTP/1.1 200 OK\r\n"
           #{"content-type: text/plain\r\n"
             "content-length: 5\r\n"
             "connection: close\r\n"}
           "\r\n"
           "Hello"))

      (is (closed-socket?)))))

(defcoretest request-with-query-string
  [ch1]
  (start-hello-world-app ch1)

  (with-socket
    (write-socket "GET /foo?bar=baz HTTP/1.1\r\n\r\n")

    (is (next-msgs
         ch1
         :open    :dont-care
         :request [{:script-name    ""
                    :path-info      "/foo"
                    :query-string   "bar=baz"
                    :request-method "GET"
                    :http-version   [1 1]} nil]
         :close nil))

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
         :open    :dont-care
         :request [#(includes-hdrs {"content-length" "5"} %) "Hello"]
         :close   nil))

    (is (no-msgs ch1))))

(defcoretest simple-request-with-chunked-body
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= [:body  nil] [evt val])
         (future
           (Thread/sleep 50)
           (dn :response [204 {} nil])))))
   {:pipeline false})

  (with-socket
    (write-socket "POST / HTTP/1.1\r\n"
                  "Connection: close\r\n"
                  "Transfer-Encoding: chunked\r\n\r\n")

    (is (next-msgs
         ch1
         :open :dont-care
         :request [#(includes-hdrs {"connection" "close" "transfer-encoding" "chunked"} %)
                   :chunked]))

    (is (no-msgs ch1))

    (write-socket "5\r\nHello\r\n")
    (is (next-msgs ch1 :body "Hello"))

    (write-socket "0\r\n\r\n")
    (is (next-msgs ch1 :body nil :close nil))

    (is (receiving
         "HTTP/1.1 204 No Content\r\n\r\n"))

    (is (closed-socket?))))

(defcoretest request-with-full-uri
  [ch1]
  (start-hello-world-app ch1)

  (with-socket
    (write-socket "GET http://www.google.com/search?q=zomg HTTP/1.1\r\n\r\n")

    (is (next-msgs
         ch1
         :open    :dont-care
         :request [{:script-name    ""
                    :path-info      "/search"
                    :query-string   "q=zomg"
                    :request-method "GET"
                    :http-version   [1 1]} nil]))
    (is (receiving "HTTP/1.1 200 OK\r\n"))))

(defcoretest non-string-response-headers
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (when (= :request evt)
         (dn :response [200 {"content-length" 5
                             "connection" "close"} (buffer "Hello")]))))
   {:pipeline false})

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n\r\n")

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         #{"content-length: 5\r\n"
           "connection: close\r\n"}
         "\r\n"
         "Hello"))

    (is (closed-socket?))))

(defcoretest honors-http-1-0-responses
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (when (= :request evt)
         (dn :response [200 {:http-version [1 0]} (buffer "Hello")]))))
   {:pipeline false})

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n\r\n")

    (is (receiving
         "HTTP/1.0 200 OK\r\n\r\n"
         "Hello"))

    (is (closed-socket?))))

(defcoretest head-request-with-content-length
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (when (= :request evt)
         (let [[hdrs] val]
           (dn :response [200 {"content-length" "10"} nil])))))
   {:pipeline false})

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
   (fn [dn _]
     (fn [evt val]
       (when (= :request evt)
         (let [[hdrs] val]
           (dn :response [200 {"content-length" "5"} (buffer "Hello")])))))
   {:pipeline false})

  (with-socket
    (write-socket "HEAD / HTTP/1.1\r\n"
                  "Connection: close\r\n\r\n")

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 5\r\n\r\n"))

    (is (closed-socket?))))

(defcoretest no-content-response-but-with-content
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (let [[hdrs] val]
           (try
             (dn :response [204 {"content-length" "5"} (buffer "Hello")])
             (catch Exception _
               (dn :response [200 {"content-length" "4"} (buffer "ZOMG")])))))))
   {:pipeline false})

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
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (let [[hdrs] val]
           (try
             (dn :response [304 {"content-length" "5"} (buffer "Hello")])
             (catch Exception _
               (dn :response [200 {"content-length" "4"} (buffer "ZOMG")])))))))
   {:pipeline false})

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
         :open :dont-care
         :request [{:script-name    ""
                    :path-info      "/"
                    :query-string   ""
                    :request-method "GET"
                    :http-version   [1 0]} nil]
         :close nil))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"))))

(defcoretest request-and-response-with-duplicated-headers
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response
             [200 {"content-length" "0"
                   "connection"     "close"
                   "foo"            "lol"
                   "bar"            ["omg" "hi2u"]
                   "baz"            ["1" "2" "3"]} (buffer "")]))))
   {:pipeline false})

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
         :open    :dont-care
         :request [{:script-name    ""
                    :path-info      "/"
                    :query-string   ""
                    :request-method "GET"
                    :http-version   [1 1]
                    "foo"           ["1" "2" "3"]
                    "bar"           ["omg" "hi2u"]
                    "baz"           "lol"} nil]
         :close nil))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         #{"content-length: 0\r\n"
           "connection: close\r\n"
           "foo: lol\r\n"
           "bar: omg\r\n"
           "bar: hi2u\r\n"
           "baz: 1\r\n"
           "baz: 2\r\n"
           "baz: 3\r\n"}
         "\r\n"))))

(defcoretest keepalive-requests
  [ch1 ch2]
  (start
   (fn [dn _]
     (enqueue ch2 [:binding nil])
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [200 {"content-length" "5"} (buffer "Hello")]))))
   {:pipeline false})

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n\r\n")

    (is (next-msgs
         ch1
         :open    :dont-care
         :request [#(includes-hdrs {:request-method "GET" :path-info "/"} %) nil]))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 5\r\n\r\n"
         "Hello"))

    (write-socket "GET /foo HTTP/1.1\r\n\r\n")

    (is (next-msgs
         ch1
         :request [#(includes-hdrs {:request-method "GET" :path-info "/foo"} %) nil]))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 5\r\n\r\n"
         "Hello"))

    (write-socket "POST /bar HTTP/1.1\r\n"
                  "connection:close\r\n\r\n")

    (is (next-msgs
         ch1
         :request [#(includes-hdrs {:request-method "POST" :path-info "/bar"} %) nil]
         :close   nil))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 5\r\n\r\n"
         "Hello"))

    (close-socket)

    (is (next-msgs ch2 :binding nil))

    (is (no-msgs ch1 ch2))))

(defcoretest keepalive-head-requests
  [ch1 ch2]
  (start
   (fn [dn _]
     (enqueue ch2 [:binding nil])
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [200 {"content-type"   "text/plain"
                             "content-length" "5"} (buffer "Hello")]))))
   {:pipeline false})

  (with-socket
    (write-socket "HEAD / HTTP/1.1\r\n\r\n")

    (is (next-msgs
         ch1
         :open    :dont-care
         :request :dont-care))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-type: text/plain\r\n"
         "content-length: 5\r\n\r\n"))

    (write-socket "HEAD / HTTP/1.1\r\n\r\n")

    (is (next-msgs
         ch1
         :request :dont-care))

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
         :close   nil))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-type: text/plain\r\n"
         "content-length: 5\r\n\r\n")))

  (is (next-msgs ch2 :binding nil))

  (is (no-msgs ch1 ch2)))

(defcoretest keepalive-204-responses
  [ch1 ch2]
  (start
   (fn [dn _]
     (enqueue ch2 [:binding nil])
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [204 {} nil]))))
   {:pipeline false})

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n\r\n")

    (is (next-msgs ch1 :open :dont-care :request :dont-care))
    (is (receiving "HTTP/1.1 204 No Content\r\n\r\n"))

    (write-socket "POST /blah HTTP/1.1\r\n"
                  "Content-Length: 5\r\n\r\n"
                  "Hello")

    (is (next-msgs ch1 :request [:dont-care "Hello"]))
    (is (receiving "HTTP/1.1 204 No Content\r\n\r\n"))

    (write-socket "GET /zomg HTTP/1.1\r\n"
                  "lulz: 4-the\r\n\r\n")

    (is (next-msgs ch1 :request :dont-care))
    (is (receiving "HTTP/1.1 204 No Content\r\n\r\n"))

    (write-socket "GET / HTTP/1.1\r\n"
                  "Connection: close\r\n\r\n")

    (is (next-msgs ch1 :request :dont-care :close nil))
    (is (receiving "HTTP/1.1 204 No Content\r\n\r\n"))

    (is (closed-socket?)))

  (is (next-msgs ch2 :binding nil))
  (is (no-msgs ch1 ch2)))

(defcoretest keepalive-304-responses
  [ch1 ch2]
  (start
   (fn [dn _]
     (enqueue ch2 [:binding nil])
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [304 {} nil]))))
   {:pipeline false})

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n\r\n")

    (is (next-msgs ch1 :open :dont-care  :request :dont-care))
    (is (receiving "HTTP/1.1 304 Not Modified\r\n\r\n"))

    (write-socket "POST /blah HTTP/1.1\r\n"
                  "Content-Length: 5\r\n\r\n"
                  "Hello")

    (is (next-msgs ch1 :request [:dont-care "Hello"]))
    (is (receiving "HTTP/1.1 304 Not Modified\r\n\r\n"))

    (write-socket "GET /zomg HTTP/1.1\r\n"
                  "lulz: 4-the\r\n\r\n")

    (is (next-msgs ch1 :request :dont-care))
    (is (receiving "HTTP/1.1 304 Not Modified\r\n\r\n"))

    (write-socket "GET / HTTP/1.1\r\n"
                  "Connection: close\r\n\r\n")

    (is (next-msgs ch1 :request :dont-care :close nil))
    (is (receiving "HTTP/1.1 304 Not Modified\r\n\r\n"))

    (is (closed-socket?)))

  (is (next-msgs ch2 :binding nil))
  (is (no-msgs ch1 ch2)))

(defcoretest returning-connection-close-terminates-connection
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [200 {"connection" "close"} (buffer "Hello")]))))
   {:pipeline false})

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n\r\n")

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "connection: close\r\n\r\n"
         "Hello"))

    (is (closed-socket?))))

(defcoretest returning-connection-close-terminates-connection-multiple-chunks
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [200 {"connection" "close"} :chunked])
         (future
           (Thread/sleep 20)
           (dn :body (buffer "hello "))
           (Thread/sleep 40)
           (dn :body (buffer "world"))
           (dn :body nil)))))
   {:pipeline false})

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n\r\n")

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "connection: close\r\n\r\n"
         "hello world"))

    (is (closed-socket?))))

(defcoretest returning-connection-close-and-chunks
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [200 {"connection" "close"} :chunked])
         (dn :body (buffer "Hello "))
         (dn :body (buffer "world"))
         (dn :body nil))))
   {:pipeline false})

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n\r\n")

    (is (next-msgs
         ch1
         :open    :dont-care
         :request :dont-care
         :close   nil))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "connection: close\r\n\r\n"
         "Hello world"))

    (is (closed-socket?))))

(defcoretest transfer-encoding-chunked-and-keep-alive
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (when (= :request evt)
         (let [[hdrs] val]
           (if (= "GET" (hdrs :request-method))
             (do
               (dn :response [200 {"transfer-encoding" "chunked"} :chunked])
               (dn :body (buffer "Hello"))
               (dn :body (buffer "World"))
               (dn :body nil))
             (dn :response [202 {"content-length" "0"}]))))))
   {:pipeline false})

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
                  "Transfer-Encoding: chunked\r\n\r\n")

    (is (next-msgs
         ch1
         :open    :dont-care
         :request [#(includes-hdrs {"transfer-encoding" "chunked"} %) :chunked]))

    (write-socket "5\r\nHello\r\n5\r\nWorld\r\n0\r\n\r\n")

    (is (next-msgs
         ch1
         :body  "Hello"
         :body  "World"
         :body  nil
         :close nil))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-type: text/plain\r\n"
         "content-length: 5\r\n"
         "connection: close\r\n\r\n"
         "Hello"))

    (is (closed-socket?))))

(defcoretest single-chunked-response
  (start
   (fn [dn _]
     (fn [evt val]
       (when (= :request evt)
         (dn :response [200 {"transfer-encoding" "chunked"} :chunked])
         (dn :body (buffer "Hello"))
         (dn :body nil))))
   {:pipeline false})

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
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (try
         (when (= :request evt)
           (dn :response [200 {"content-length" "5"} :chunked])
           (dn :body (buffer "Hello"))
           (dn :body nil))
         (catch Exception err
           (enqueue ch2 [:error err])))))
   {:pipeline false})

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n\r\n")

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 5\r\n\r\n"
         "Hello"))

    (is (next-msgs
         ch1
         :open    :dont-care
         :request :dont-care))

    (is (no-msgs ch1 ch2))))

(defcoretest chunked-requests-keep-alive
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [200 {"content-length" "5"} (buffer "Hello")]))))
   {:pipeline false})

  (with-socket
    (write-socket "POST / HTTP/1.1\r\n"
                  "Transfer-Encoding: chunked\r\n\r\n"
                  "5\r\nHello\r\n6\r\n World\r\n0\r\n\r\n")

    (is (next-msgs
         ch1
         :open    :dont-care
         :request [#(includes-hdrs {"transfer-encoding" "chunked"} %) :chunked]
         :body    "Hello"
         :body    " World"
         :body    nil))

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
         :body    nil))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 5\r\n\r\n"
         "Hello"))

    (write-socket "GET / HTTP/1.1\r\n"
                  "Connection: close\r\n\r\n")

    (is (next-msgs
         ch1
         :request [#(includes-hdrs {"connection" "close"} %) nil]
         :close nil))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 5\r\n\r\n"
         "Hello"))))

(defcoretest aborting-a-request
  [ch1]
  (start-hello-world-app ch1)

  (with-socket
    (write-socket "POST / HTTP/1.1\r\n"
                  "Content-Length: 10000\r\n\r\n"
                  "TROLLOLOLOLOLOLLLOLOLOLLOL")

    (close-socket)

    (is (next-msgs
         ch1
         :open    :dont-care
         :request [:dont-care :chunked]
         :body    "TROLLOLOLOLOLOLLLOLOLOLLOL"
         :abort   #(instance? Exception %))))

  (is (no-msgs ch1)))

(defcoretest request-callback-happens-before-body-is-recieved
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (when-not (= :body evt)
         (enqueue ch1 [evt val]))

       (when (= :request evt)
         (dn :response [200 {"content-type"   "text/plain"
                             "content-length" "5"
                             "connection"     "close"} (buffer "Hello")]))))
   {:pipeline false})

  (with-socket
    (write-socket "POST / HTTP/1.1\r\n"
                  "Connection: close\r\n"
                  "Content-Length: 10000\r\n\r\n")

    (is (next-msgs
         ch1
         :open    :dont-care
         :request [#(includes-hdrs
                     {"connection"     "close"
                      "content-length" "10000"} %) :chunked]))

    (is (no-msgs ch1))

    (write-socket (apply str (for [x (range 10000)] "a")))

    (is (next-msgs ch1 :close nil))))

(defcoretest handling-100-continue-requests-with-100-response
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (cond
        (= :request evt)
        (dn :response [100])

        (= [:body nil] [evt val])
        (dn :response [200 {"content-length" "5"} (buffer "Hello")]))))
   {:pipeline false})

  (with-socket
    (write-socket "POST / HTTP/1.1\r\n"
                  "Content-Length: 5\r\n"
                  "Connection: close\r\n"
                  "Expect: 100-continue\r\n\r\n")

    (is (next-msgs
         ch1
         :open    :dont-care
         :request [#(includes-hdrs {"expect" "100-continue"} %) :chunked]))

    (is (receiving "HTTP/1.1 100 Continue\r\n"))

    (write-socket "Hello")

    (is (next-msgs
         ch1
         :body  "Hello"
         :body  nil
         :close nil))))

(defcoretest handling-100-continue-requests-by-responding-directly
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (let [[{request-method :request-method}] val]
           (if (= "GET" request-method)
             (dn :response [200 {"content-length" "5"} (buffer "Hello")])
             (dn :response [417 {"content-length" "0"} (buffer "")]))))))
   {:pipeline false})

  (with-socket
    (write-socket "POST / HTTP/1.1\r\n"
                  "Content-Length: 5\r\n"
                  "Expect: 100-continue\r\n\r\n")

    (is (next-msgs
         ch1
         :open    :dont-care
         :request [#(includes-hdrs {"expect" "100-continue"} %) :chunked]))

    (is (receiving
         "HTTP/1.1 417 Expectation Failed\r\n"
         "content-length: 0\r\n\r\n"))

    (write-socket "Hello"
                  "GET / HTTP/1.1\r\n"
                  "Connection: close\r\n\r\n")

    (is (next-msgs
         ch1
         :body    "Hello"
         :body    nil
         :request [#(includes-hdrs {:request-method "GET"} %) nil]
         :close   nil))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 5\r\n\r\n"
         "Hello"))

    (is (no-msgs ch1))
    (is (closed-socket?))))

(defcoretest sending-multiple-100-continue-responses
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (cond
        (= :request evt)
        (try
          (dn :response [100])
          (dn :response [100])
          (catch Exception err
            (enqueue ch1 [:error err])))

        (= [:body nil] [evt val])
        (dn :response [204 {"connection" "close"} nil]))))
   {:pipeline false})

  (with-socket
    (write-socket "POST / HTTP/1.1\r\n"
                  "Content-Length: 5\r\n"
                  "Expect: 100-continue\r\n\r\n")

    (is (next-msgs ch1 :error #(instance? Exception %)))

    (is (receiving "HTTP/1.1 100 Continue\r\n\r\n"))

    (write-socket "Hello")

    (is (receiving
         "HTTP/1.1 204 No Content\r\n"
         "connection: close\r\n\r\n"))

    (is (closed-socket?))))

(defcoretest client-sends-body-and-expects-100
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= [:body nil] [evt val])
         (dn :response [204 {"connection" "close"} nil]))))
   {:pipeline false})

  (with-socket
    (write-socket "POST / HTTP/1.1\r\n"
                  "Content-Length: 5\r\n"
                  "Expect: 100-continue\r\n\r\n"
                  "Hello")

    (is (next-msgs
         ch1
         :open    :dont-care
         :request [#(includes-hdrs {"expect" "100-continue"} %) :chunked]
         :body    "Hello"
         :body    nil
         :close   nil))

    (is (receiving
         "HTTP/1.1 204 No Content\r\n"
         "connection: close\r\n\r\n"))))

(defcoretest client-sends-body-and-expects-100-2
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (cond
        (= :request evt)
        (dn :response [100])

        (= [:body nil] [evt val])
        (dn :response [204 {"connection" "close"} nil]))))
   {:pipeline false})

  (with-socket
    (write-socket "POST / HTTP/1.1\r\n"
                  "Content-Length: 5\r\n"
                  "Expect: 100-continue\r\n\r\n"
                  "Hello")

    (is (next-msgs
         ch1
         :open    :dont-care
         :request [#(includes-hdrs {"expect" "100-continue"} %) :chunked]
         :body    "Hello"
         :body    nil
         :close   nil))

    (is (receiving
         "HTTP/1.1 100 Continue\r\n\r\n"
         "HTTP/1.1 204 No Content\r\n"
         "connection: close\r\n\r\n"))))

(defcoretest sending-100-continue-to-1-0-client
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (try
           (dn :response [100])
           (catch Exception err
             (enqueue ch1 [:error err])))
         (dn :response [204 {} nil]))))
   {:pipeline false})

  (with-socket
    (write-socket "POST / HTTP/1.0\r\n"
                  "Content-Length: 5\r\n"
                  "Expect: 100-continue\r\n\r\n"
                  "Hello")

    (let [expected-hdrs {:http-version [1 0]
                         "expect" "100-continue"}]
      (is (next-msgs
           ch1
           :open    :dont-care
           :request [#(includes-hdrs expected-hdrs %) "Hello"]
           :error   #(instance? Exception %)
           :close   nil))

      (is (no-msgs ch1)))))

(defcoretest timing-out-without-writing-request
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])))
   {:pipeline false :keepalive 1})

  (with-socket
    (Thread/sleep 2010)
    (is (closed-socket?))

    (is (next-msgs
         ch1
         :open  :dont-care
         :close nil))))

(defcoretest timing-out-halfway-streamed-chunked-request
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])))
   {:pipeline false :timeout 1})

  (with-socket
    (write-socket "POST / HTTP/1.1\r\n"
                  "Transfer-Encoding: chunked\r\n\r\n"
                  "5\r\nHello\r\n5\r\nWorld")

    (is (next-msgs
         ch1
         :open    :dont-care
         :request [:dont-care :chunked]
         :body    "Hello"
         :body    "World"))

    (Thread/sleep 2000)

    (is (next-msgs ch1 :abort #(instance? Exception %)))

    (is (receiving ""))
    (is (closed-socket?))))

(defcoretest each-event-resets-timer
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])))
   {:pipeline false :timeout 1})

  (with-socket
    (write-socket "POST / HTTP/1.1\r\n"
                  "Transfer-Encoding: chunked\r\n\r\n")

    (is (next-msgs ch1 :open :dont-care :request :dont-care))

    (Thread/sleep 800)

    (write-socket "5\r\nHello\r\n")

    (is (next-msgs ch1 :body "Hello"))

    (Thread/sleep 800)

    (write-socket "5\r\nWorld\r\n")

    (is (next-msgs ch1 :body "World"))

    (Thread/sleep 800)

    (write-socket "0\r\n\r\n")

    (is (next-msgs ch1 :body nil))))

(defcoretest timing-out-halfway-through-streamed-chunked-response
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [200 {"transfer-encoding" "chunked"} :chunked])
         (dn :body (buffer "Hello"))
         (dn :body (buffer "World")))))
   {:pipeline false :timeout 1})

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n\r\n")
    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "transfer-encoding: chunked\r\n\r\n"
         "5\r\nHello\r\n5\r\nWorld\r\n"))

    (Thread/sleep 2010)

    (is (closed-socket?))))

(defcoretest timing-out-during-keepalive
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (when (= :abort evt)
         (.printStackTrace val))
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [200 {"content-length" "5"} (buffer "Hello")]))))
   {:pipeline false :keepalive 1})

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n\r\n")

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 5\r\n\r\n"
         "Hello"))

    (Thread/sleep 2010)

    (is (closed-socket?))))

(defcoretest closing-connection-during-keepalive
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [200 {"content-length" "5"} (buffer "Hello")]))))
   {:pipeline false})

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n\r\n")

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 5\r\n\r\n"
         "Hello"))

    (Thread/sleep 100)
    (close-socket)

    (is (next-msgs
         ch1
         :open    :dont-care
         :request :dont-care
         :close   nil))

    (is (no-msgs ch1))))

(defcoretest closing-the-connection-immedietly-after-receiving-body
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [200 {"content-length" 5} :chunked])
         (dn :body (buffer "Hello")))))
   {:pipeline false})

  (with-socket
    (write-socket "GET / HTTP/1.1\r\n\r\n")

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 5\r\n\r\n"
         "Hello"))

    (close-socket)

    (is (next-msgs
         ch1
         :open    :dont-care
         :request :dont-care
         :close   nil))

    (is (no-msgs ch1))))

(defcoretest hard-closing-socket-before-response
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :close nil))))
   {:pipeline false})

  (with-socket
    (write-socket
     "GET / HTTP/1.1\r\n"
     "Host: localhost\r\n"
     "\r\n")

    (is (next-msgs
         ch1
         :open    :dont-care
         :request :dont-care
         :close   nil))

    (is (closed-socket?))))

(defcoretest hard-closing-socket-while-streaming-request
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :body evt)
         (dn :close nil))))
   {:pipeline false})

  (with-socket
    (write-socket
     "POST / HTTP/1.1\r\n"
     "Host: localhost\r\n"
     "Transfer-Encoding: chunked\r\n"
     "\r\n"
     "5\r\nHello\r\n"
     "5\r\nWorld\r\n"
     "0\r\n\r\n")

    (is (next-msgs
         ch1
         :open    :dont-care
         :request :dont-care
         :body    "Hello"
         :body    "World"
         :body    nil
         :close   nil))

    (is (closed-socket?))))

(defcoretest hard-closing-socket-while-streaming-response
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [200 {"transfer-encoding" "chunked"} :chunked])
         (dn :body (buffer "Hello"))
         (dn :close nil))))
   {:pipeline false})

  (with-socket
    (write-socket
     "GET / HTTP/1.1\r\n"
     "Host: localhost\r\n"
     "\r\n")

    (is (next-msgs
         ch1
         :open    :dont-care
         :request :dont-care
         :close   nil))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "transfer-encoding: chunked\r\n"
         "\r\n"
         "5\r\nHello\r\n"))

    (is (closed-socket?))))

;; Upgrading the connection
(defcoretest upgrading-the-connection-to-echo-server
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (when (= :abort evt)
         (println (.getMessage val))
         (.printStackTrace val))
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [101 {"connection" "upgrade" "upgrade" "echo"} :upgraded]))
       (when (= :message evt)
         (dn :message val))))
   {:pipeline false})

  (with-socket
    (write-socket
     "GET / HTTP/1.1\r\n"
     "Host: localhost\r\n"
     "Connection: Upgrade\r\n"
     "Upgrade: echo\r\n"
     "\r\n")

    (is (next-msgs
         ch1
         :open    :dont-care
         :request [#(includes-hdrs {"connection" "upgrade"
                                    "upgrade" "echo"} %) :upgraded]))

    (is (receiving
         "HTTP/1.1 101 Switching Protocols\r\n"
         #{"connection: upgrade\r\n"
           "upgrade: echo\r\n"}
         "\r\n"))

    (write-socket "HELLO")
    (Thread/sleep 100)
    (is (next-msgs ch1 :message "HELLO"))

    (write-socket "GOODBYE")
    (Thread/sleep 100)
    (is (next-msgs ch1 :message "GOODBYE"))

    (close-socket)
    (is (next-msgs ch1 :close nil))))

(defcoretest denying-an-upgrade-request
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [400 {"content-length" "5"} (buffer "FAIL!")]))))
   {:pipeline false})

  (with-socket
    (write-socket
     "GET / HTTP/1.1\r\n"
     "Host: localhost\r\n"
     "Connection: upgrade\r\n"
     "Upgrade: echo\r\n"
     "\r\n")

    (is (next-msgs
         ch1
         :open    :dont-care
         :request [#(includes-hdrs {"connection" "upgrade"} %) :upgraded]))

    (is (receiving
         "HTTP/1.1 400 Bad Request\r\n"
         "content-length: 5\r\n"
         "\r\n"
         "FAIL!"))

    (is (closed-socket?))))

;; ==== HTTP pipelining

(defcoretest serial-pipelined-requests-are-handled-correctly
  [ch1 ch2]
  (start
   (fn [dn _]
     (enqueue ch1 [:binding nil])
     (doasync (seq ch2)
       (fn [_] (dn :response [200 {"content-length" "5"} (buffer "Hello")])))
     (fn [evt val]
       (enqueue ch1 [evt val]))))

  (with-socket
    (write-socket
     "GET / HTTP/1.1\r\n\r\n"
     "GET / HTTP/1.1\r\n\r\n")

    (is (next-msgs
         ch1
         :binding nil
         :request [{:local-addr     ["127.0.0.1" 4040]
                    :remote-addr    ["127.0.0.1" :dont-care]
                    :request-method "GET"
                    :path-info      "/"
                    :script-name    ""
                    :query-string   ""
                    :http-version   [1 1]} nil]

         :binding nil
         :request [{:local-addr     ["127.0.0.1" 4040]
                    :remote-addr    ["127.0.0.1" :dont-care]
                    :request-method "GET"
                    :path-info      "/"
                    :script-name    ""
                    :query-string   ""
                    :http-version   [1 1]} nil]))

    (enqueue ch2 :tick)

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 5\r\n\r\n"
         "Hello"
         "HTTP/1.1 200 OK\r\n"
         "content-length: 5\r\n\r\n"
         "Hello"))))

(defcoretest pipelined-requests-have-the-responses-returned-in-order
  (start
   (fn [dn _]
     (fn [evt val]
       (when (= :request evt)
         (let [[{path-info :path-info} _] val]
           (future
             (when (= "/foo" path-info)
               (Thread/sleep 50))
             (dn :response [200 {"content-length" "4"} (buffer path-info)])))))))

  (with-socket
    (write-socket
     "GET /foo HTTP/1.1\r\n\r\n"
     "GET /bar HTTP/1.1\r\n\r\n")

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 4\r\n\r\n"
         "/foo"
         "HTTP/1.1 200 OK\r\n"
         "content-length: 4\r\n\r\n"
         "/bar"))))

(defcoretest pipelined-requests-return-chunked-bodies-in-order
  (start
   (fn [dn _]
     (fn [evt val]
       (when (= :request evt)
         (let [[{path-info :path-info} _] val]
           (future
             (when (= "/foo" path-info)
               (Thread/sleep 50))
             (dn :response [200 {"content-length" "20"} :chunked])
             (dotimes [_ 5] (dn :body (buffer path-info)))
             (dn :body nil)))))))

  (with-socket
    (write-socket
     "GET /foo HTTP/1.1\r\n\r\n"
     "GET /bar HTTP/1.1\r\n\r\n")

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 20\r\n\r\n"
         "/foo/foo/foo/foo/foo"
         "HTTP/1.1 200 OK\r\n"
         "content-length: 20\r\n\r\n"
         "/bar/bar/bar/bar/bar"))))

(defcoretest pipelines-at-most-specified-number-of-requests-at-a-time
  [ch1]
  (start
   (fn [dn _]
     (enqueue ch1 [:binding nil])
     (fn [evt val]
       (when (= :request evt)
         (future
           (Thread/sleep 250)
           (dn :response [204 {} nil])))))
   {:pipeline 2})

  (with-socket
    (write-socket
     "GET /foo HTTP/1.1\r\n\r\n"
     "GET /bar HTTP/1.1\r\n\r\n"
     "GET /baz HTTP/1.1\r\n\r\n")

    (is (next-msgs ch1 :binding nil :binding nil))
    (is (no-msgs ch1))

    (Thread/sleep 400)

    (is (next-msgs ch1 :binding nil))

    (is (receiving
         "HTTP/1.1 204 No Content\r\n\r\n"
         "HTTP/1.1 204 No Content\r\n\r\n"
         "HTTP/1.1 204 No Content\r\n\r\n"))))

(defcoretest pausing-pipelined-chunked-requests
  [ch1]
  (start
   (fn [dn _]
     (enqueue ch1 [:binding nil])
     (fn [evt val]
       (when (= [:body nil] [evt val])
         (future
           (Thread/sleep 250)
           (dn :response [204 {} nil])))))
   {:pipeline 1})

  (with-socket
    (write-socket
     "POST /foo HTTP/1.1\r\n"
     "Transfer-Encoding: chunked\r\n\r\n"
     "3\r\nfoo\r\n"
     "3\r\nbar\r\n"
     "3\r\nbaz\r\n"
     "0\r\n\r\n"
     "POST /bar HTTP/1.1\r\n"
     "Transfer-encoding: chunked\r\n\r\n"
     "3\r\nFOO\r\n"
     "3\r\nBAR\r\n"
     "3\r\nBAZ\r\n"
     "0\r\n\r\n")

    (is (next-msgs ch1 :binding nil))
    (is (no-msgs ch1))

    (Thread/sleep 400)

    (is (next-msgs ch1 :binding nil))

    (is (receiving
         "HTTP/1.1 204 No Content\r\n\r\n"
         "HTTP/1.1 204 No Content\r\n\r\n"))))

(defcoretest upstream-close-events-get-fanned-out
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val]))))

  (with-socket
    (write-socket
     "GET /foo HTTP/1.1\r\n\r\n"
     "GET /bar HTTP/1.1\r\n\r\n"
     "GET /baz HTTP/1.1\r\n\r\n")

    (close-socket)

    (is (next-msgs
         ch1
         :request :dont-care
         :request :dont-care
         :request :dont-care
         :close   nil
         :close   nil
         :close   nil))))

(defcoretest upstream-abort-events-get-fanned-out
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (let [[{path-info :path-info} _] val]
           (when (= "/foo" path-info)
             (future (Thread/sleep 50)
                     (dn :abort (Exception.)))))))))

  (with-socket
    (write-socket
     "GET /foo HTTP/1.1\r\n\r\n"
     "GET /bar HTTP/1.1\r\n\r\n")

    (is (next-msgs
         ch1
         :request :dont-care
         :request :dont-care
         :abort   :dont-care
         :abort   :dont-care))))

(defcoretest exceptions-in-pending-pipelined-handlers-cancel-connection
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (let [[{path-info :path-info} _] val]
           (when (= "/bar" path-info)
             (throw (Exception. "BOOM"))))))))

  (with-socket
    (write-socket
     "GET /foo HTTP/1.1\r\n\r\n"
     "GET /bar HTTP/1.1\r\n\r\n")

    (is (next-msgs
         ch1
         :request :dont-care
         :request :dont-care
         :abort   :dont-care
         :abort   :dont-care))

    (is (receiving ""))
    (is (closed-socket?))))

(defcoretest aborts-in-pending-pipelined-handlers-cancel-connection
  [ch1]
  (start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (let [[{path-info :path-info} _] val]
           (when (= "/bar" path-info)
             (dn :abort (Exception. "BOOM"))))))))

  (with-socket
    (write-socket
     "GET /foo HTTP/1.1\r\n\r\n"
     "GET /bar HTTP/1.1\r\n\r\n")

    (is (next-msgs
         ch1
         :request :dont-care
         :request :dont-care
         :abort   :dont-care
         :abort   :dont-care))))
