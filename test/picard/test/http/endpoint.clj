(ns picard.test.http.endpoint
  (:use
   clojure.test
   picard.core
   picard.http
   picard.http.test))

(deftest very-simple-endpoint
  (with-app
    (endpoint
     (GET "/foo" [request]
       (respond :text "hello")))

    (GET "/foo")
    (is (= 200 (response-status)))

    (GET "/")
    (is (= 404 (response-status)))))

(deftest endpoint-works-with-deferred-values
  (with-app
    (endpoint
     (GET "/foo" [request]
       (future*
        (Thread/sleep 10)
        (respond :text "Hello"))))

    (GET "/foo")
    (is (= 200 (response-status)))
    (is (= (buffer "Hello") (response-body)))))

(deftest request-provides-headers
  (with-app
    (endpoint
     (GET "/foo" [request]
       (respond :text (request "foo"))))

    (GET "/foo" {"foo" "Zomg"})
    (is (= 200 (response-status)))
    (is (= (buffer "Zomg") (response-body)))))

(deftest getting-the-request-body
  (let [res (atom [])]
    (with-app
      (endpoint
       (POST "/foo" [request]
         (doasync (request :body)
           (fn [[chunk & more]]
             (if chunk
               (do
                 (swap! res #(conj % chunk))
                 (recur* more))
               (respond :text "Done"))))))

      (POST "/foo" :chunked)
      (chunks "one" "two" "three" nil)

      (is (= 200 (response-status)))
      (is (= (buffer "Done") (response-body)))
      (is (= (map #(buffer %) ["one" "two" "three"]) @res)))))
