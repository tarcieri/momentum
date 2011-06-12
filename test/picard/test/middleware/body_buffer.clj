(ns picard.test.middleware.body-buffer
  (:use
   [clojure.test]
   [picard.helpers]
   [picard.test])
  (:require
   [picard.middleware :as middleware]))

(def hello-world-app
  (fn [downstream]
    (fn [evt val]
      (when (= :request evt)
        (downstream :response [200 {"content-length" "5"} "Hello"])))))

(def hello-world-app
  (fn [downstream]
    (defstream
      (request [_]
        (downstream :response [200 {"content-length" "5"} "Hello"])))))

(def echo-app
  (fn [downstream]
    (defstream
      (request [[_ body]]
        (downstream :response [200 {} body]))
      (body [chunk] (downstream :body chunk)))))

(def chunked-app
  (fn [downstream]
    (defstream
      (request [_]
        (downstream :response [200 {} :chunked])
        (downstream :body (to-channel-buffer "Hello"))
        (downstream :body (to-channel-buffer "World"))
        (downstream :body nil)))))

(deftest passes-simple-requests-through
  (with-app (middleware/body-buffer hello-world-app)
    (GET "/")
    (is (= (last-response)
           [200 {"content-length" "5"} "Hello"]))))

(deftest buffers-chunked-requests
  (with-app (middleware/body-buffer echo-app)
    (let [upstream (GET "/" :chunked)]
      (is (empty? (received-exchange-events (last-exchange))))

      (upstream :body (to-channel-buffer "Hello"))
      (is (empty? (received-exchange-events (last-exchange))))

      (upstream :body (to-channel-buffer "World"))
      (is (empty? (received-exchange-events (last-exchange))))

      (upstream :body nil)
      (is (= (last-response) [200 {} "HelloWorld"])))))

(deftest buffers-chunked-responses
  (with-app (middleware/body-buffer chunked-app)
    (GET "/")
    (is (= (last-response)
           [200 {} "HelloWorld"]))))

(deftest disabling-buffering-on-request
  (with-app (middleware/body-buffer
             (fn [downstream]
               (fn [evt val]
                 (when (request-done? evt val)
                   (downstream :response [200 {} "YAY"]))))
             {:upstream false})
    (let [upstream (GET "/")]
      (upstream :body "Hello")
      (upstream :body "World")
      (upstream :body nil))

    (is (= (last-response)
           [200 {} "YAY"]))))

(deftest disabling-buffering-on-response
  (with-app (middleware/body-buffer chunked-app
                                    {:downstream false})
    (GET "/")

    (is (= (last-response)
           [200 {} :chunked]))

    (is (= (last-body-chunks)
           ["Hello" "World"]))))
