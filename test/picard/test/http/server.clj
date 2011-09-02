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

      (is (not (open-socket?))))))

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
                    :http-version   [1 1]} nil]))))

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

    (is (not (open-socket?)))))

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

    (is (not (open-socket?)))))

(defcoretest head-request-with-content-length
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (when (= :abort evt)
         (.printStackTrace val))
       (when (= :request evt)
         (let [[hdrs] val]
           (when (= "HEAD" (hdrs :request-method))
             (dn :response [200 {"content-length" "10"} nil])))))))

  (with-socket
    (write-socket "HEAD / HTTP/1.1\r\n"
                  "Connection: close\r\n\r\n")

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 10\r\n\r\n"))

    (is (not (open-socket?)))))
