(ns picard.test.middleware.retry
  (:use
   [clojure.test]
   [picard.helpers]
   [picard.test])
  (:require
   [picard.middleware :as middleware]))

(deftest simple-requests-get-through
  (with-app
    (-> (fn [downstream]
          (defstream
            (request [_]
              (downstream :response [202 {"content-length" "0"} ""]))))
        middleware/retry)
    (GET "/")
    (is (= 202 (last-response-status)))))

(deftest handles-chunked-failures
  (let [latch (atom false)]
    (with-app
      (-> (fn [dn]
            (defstream
              (request [req]
                (if-not @latch
                  (do
                    (dn :response [500 {"transfer-encoding" "chunked"} :chunked])
                    (dn :body "foo\n")
                    (dn :body "bar\n")
                    (dn :body "baz\n")
                    (dn :body nil)
                    (reset! latch true))
                  (do
                    (dn :response [200 {"transfer-encoding" "chunked"} :chunked])
                    (dn :body "hello\n")
                    (dn :body "world\n")
                    (dn :body nil))))))
          (middleware/retry {:retries [5 5]}))

      (GET "/")
      (is (= 200 (last-response-status)))
      (is (= ["hello\n" "world\n"] (last-body-chunks)))
      )))

(deftest retries-when-application-returns-standard-retry-codes
  (doseq [status [408 500 502 503 504]]
    (with-app
      (let [latch (atom true)]
        (-> (fn [downstream]
              (defstream
                (request [_]
                  (if @latch
                    (do (reset! latch false)
                        (downstream :response [status {"content-length" "0"} ""]))
                    (downstream :response [202 {"content-length" "0"} ""])))))
            (middleware/retry {:retries [5 5]})))
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
              (defstream
                (request [_]
                  (if @latch
                    (do (reset! latch false)
                        (downstream :response [status {"content-length" "0"} ""]))
                    (downstream :response [202 {"content-length" "0"} ""])))))
            (middleware/retry {:retries [5 5]})))

      (GET "/")
      (is (= status (first (second (first (exchange-events (last-exchange))))))))))

(deftest doesnt-retry-when-the-body-is-sent
  (with-app
    (let [latch (atom true)]
      (-> (fn [downstream]
            (defstream
              (request [_]
                (if @latch
                  (do (reset! latch false)
                      (timeout
                       10 #(downstream :response [500 {"content-length" "0"} ""])))
                  (downstream :response [202 {"content-length" "0"} ""])))))
          middleware/retry))
    ;; Send the request and body directly after
    (let [upstream (GET "/" :chunked)]
      (upstream :body "hello")
      (upstream :body nil))
    ;; Make sure the response is 500
    (is (= 500 (last-response-status)))))

(deftest respect-retry-count
  (let [count (atom 0)]
    (with-app
      (-> (fn [downstream]
            (defstream
              (request [_]
                (swap! count inc)
                (downstream :response [500 {"content-length" "0"} ""]))))
          (middleware/retry {:retries [5 5 5]}))
      (GET "/")
      (is (= 500 (last-response-status)))
      (is (= 4 @count)))))

(deftest respect-backoff-times
  (let [count (atom 0)]
    (with-app
      (-> (fn [downstream]
            (defstream
              (request [_]
                (swap! count inc)
                (downstream :response [500 {"content-length" "0"} ""]))))
          (middleware/retry {:retries [200 600 400]}))

      ;; Something to keep in mind with this test is that the timing
      ;; is done with a HashedWheelTimer which is not at all exact.
      ;; The default one uses slots of 100ms.

      (GET "/")
      (is (= 1 @count))

      (Thread/sleep 100)
      (is (= 1 @count))

      (Thread/sleep 200)
      (is (= 2 @count))

      (Thread/sleep 400)
      (is (= 2 @count))

      (Thread/sleep 300)
      (is (= 3 @count))

      (Thread/sleep 200)
      (is (= 3 @count))

      (Thread/sleep 400)
      (is (= 4 @count)))))

(deftest respect-custom-response-validators
  (let [count (atom 0)]
    (with-app
      (-> (fn [downstream]
            (defstream
              (request [_]
                (swap! count inc)
                (downstream :response [500 {"content-length" "0"} ""]))))
          (middleware/retry {:validate-response-with
                             (fn [_] true)}))
      (GET "/")
      (is (= 500 (last-response-status)))
      (is (= 1 @count)))))
