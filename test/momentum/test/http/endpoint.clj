(ns momentum.test.http.endpoint
  (:use
   clojure.test
   momentum.core
   momentum.http
   momentum.http.test))

(deftest very-simple-endpoint
  (with-endpoint
    (endpoint
     (GET "/foo" [request]
       (respond :text "hello")))

    (GET "/foo")
    (is (= 200 (response-status)))

    (GET "/")
    (is (= 404 (response-status)))))

(deftest endpoint-works-with-deferred-values
  (with-endpoint
    (endpoint
     (GET "/foo" [request]
       (future*
        (Thread/sleep 10)
        (respond :text "Hello"))))

    (GET "/foo")
    (is (= 200 (response-status)))
    (is (= (buffer "Hello") (response-body)))))

(deftest request-provides-headers
  (with-endpoint
    (endpoint
     (GET "/foo" [request]
       (respond :text (request "foo"))))

    (GET "/foo" {"foo" "Zomg"})
    (is (= 200 (response-status)))
    (is (= (buffer "Zomg") (response-body)))))

(deftest ^{:focus true} getting-the-request-body
  (let [res (atom [])]
    (with-endpoint
      (endpoint
       (POST "/foo" [request]
         (doasync (request :input)
           (fn [[chunk & more]]
             (if chunk
               (do
                 (swap! res #(conj % chunk))
                 (recur* more))
               (respond :text "Done"))))))

      (POST "/foo" {"transfer-encoding" "chunked"} :chunked)
      (send-chunks "one" "two" "three" nil)

      (is (= 200 (response-status)))
      (is (= (buffer "Done") (response-body)))
      (is (= (map #(buffer %) ["one" "two" "three"]) @res)))))

(deftest streaming-response-body
  (with-endpoint
    (endpoint
     (GET "/foo" [request]
       (let [ch (channel)]
         (future
           (dotimes [i 3]
             (Thread/sleep 10)
             (put ch (buffer (str "Chunk " i))))
           (close ch))
         (respond :text (seq ch)))))

    (GET "/foo")
    (is (= :chunked (response-body)))))
