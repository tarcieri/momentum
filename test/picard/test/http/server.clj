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
           :request [{:script-name ""
                      :path-info   "/"
                      :query-string ""
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
         :request [{:script-name    ""
                    :path-info      "/foo"
                    :query-string   "bar=baz"
                    :request-method "GET"
                    :http-version   [1 1]} nil]
         :done nil))

    (is (no-msgs ch1))))
