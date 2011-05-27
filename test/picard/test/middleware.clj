(ns picard.test.middleware
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

(deftest retries-when-application-returns-500
  (with-app
    (let [latch (atom true)]
      (-> (fn [downstream]
            (defupstream
              (request [_]
                (if @latch
                  (do (reset! latch false)
                      (downstream :response [500 {"content-length" "0"}]))
                  (downstream :response [202 {"content-length" "0"}])))))
          middleware/retry))
    (GET "/")
    (is (= 202 (last-response-status)))))
