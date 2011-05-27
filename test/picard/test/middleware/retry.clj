(ns picard.test.middleware.retry
  (:use
   [clojure.test]
   [picard.api]
   [picard.test])
  (:require
   [picard.middleware :as middleware]))

(deftest simple-requests-get-through
  (with-app
    (-> (fn [downstream]
          (defupstream
            (request [_]
              (downstream :response [202 {"content-length" "0"}]))))
        middleware/retry)
    (GET "/")
    (is (= 202 (last-response-status)))))

(deftest retries-when-application-returns-standard-retry-codes
  (doseq [status [408 500 502 503 504]]
    (with-app
      (let [latch (atom true)]
        (-> (fn [downstream]
              (defupstream
                (request [_]
                  (if @latch
                    (do (reset! latch false)
                        (downstream :response [status {"content-length" "0"}]))
                    (downstream :response [202 {"content-length" "0"}])))))
            middleware/retry))
      (GET "/")
      (is (= 202 (last-response-status))))))

(deftest doesnt-retry-when-application-returns-with-other-codes
  (doseq [status [100 101
                  200 201 202 203 204 205 206
                  300 301 302 303 304 305 306 307
                  400 401 402 403 404 405 406 407 409 410
                  411 412 413 414 415 416 417 501 505]]
    (with-app
      (let [latch (atom true)]
        (-> (fn [downstream]
              (defupstream
                (request [_]
                  (if @latch
                    (do (reset! latch false)
                        (downstream :response [status {"content-length" "0"}]))
                    (downstream :response [202 {"content-length" "0"}])))))
            middleware/retry))
      (GET "/")
      (is (= status (last-response-status))))))

(deftest doesnt-retry-when-the-body-is-sent
  (with-app
    (let [latch (atom true)]
      (-> (fn [downstream]
            (defupstream
              (request [_]
                (if @latch
                  (do (reset! latch false)
                      (timeout
                       10 #(downstream :response [500 {"content-length" "0"}])))
                  (downstream :response [202 {"content-length" "0"}])))))
          middleware/retry))
    ;; Send the request and body directly after
    (let [upstream (GET "/" :chunked)]
      (upstream :body "hello")
      (upstream :done nil))
    ;; Make sure the response is 500
    (is (= 500 (last-response-status)))))

(deftest respect-retry-count
  (let [count (atom 0)]
    (with-app
      (-> (fn [downstream]
            (defupstream
              (request [_]
                (swap! count inc)
                (downstream :response [500 {"content-length" "0"}]))))
          (middleware/retry {:retries [5 5 5]}))
      (GET "/")
      (is (= 500 (last-response-status)))
      (is (= 4 @count)))))
