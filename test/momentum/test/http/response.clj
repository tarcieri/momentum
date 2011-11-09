(ns momentum.test.http.response
  (:use
   clojure.test
   momentum.core
   momentum.http.response))

(deftest response-with-no-body
  (is (= (respond :status 204)
         [204 {} nil])))

(deftest simple-text-response
  (is (= (respond :text "Hello")
         [200 {"content-type" "text/plain" "content-length" "5"} (buffer "Hello")]))

  (is (= (respond :text ["One" "Two"])
         [200 {"content-type" "text/plain" "transfer-encoding" "chunked"} ["One" "Two"]]))

  (is (= (respond :text "Hello" "transfer-encoding" "chunked")
         (respond :text "Hello" :headers {"transfer-encoding" "chunked"})
         [200 {"transfer-encoding" "chunked" "content-type" "text/plain"} (buffer "Hello")]))

  (is (= (respond :text "Hello" :status 202)
         [200 {"content-type" "text/plain" "content-length" "5"} (buffer "Hello")])))

(deftest unsupported-formats
  (is (thrown-with-msg?
        Exception #"Unsupported"
        (respond :nan "one"))))
