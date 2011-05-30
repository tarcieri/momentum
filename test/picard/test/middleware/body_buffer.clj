(ns picard.test.middleware.body-buffer
  (:use
   [clojure.test]
   [picard.api]
   [picard.test])
  (:require
   [picard.middleware :as middleware]))

(def hello-world-app
  (-> (fn [downstream]
        (defupstream
          (request [_]
            (downstream :response [200 {"content-length" "5"} "Hello"]))))
      middleware/body-buffer))

(def echo-app
  (-> (fn [downstream]
        (defupstream
          (request [[_ body]]
            (downstream :response [200 {} body]))))
      middleware/body-buffer))

(deftest passes-simple-requests-through
  (with-app hello-world-app
    (GET "/")
    (is (= (last-response)
           [200 {"content-length" "5"} "Hello"]))))

(deftest ^{:focus true} buffers-chunked-requests
  (with-app echo-app
    (let [upstream (GET "/" :chunked)]
      (is (empty? (received-exchange-events (last-exchange))))

      (upstream :body (to-channel-buffer "Hello"))

      (upstream :body (to-channel-buffer "World"))

      (upstream :done nil)
      (is (= (last-response)
             [200 {} "HelloWorld"])))))

;; TODO: more tests
