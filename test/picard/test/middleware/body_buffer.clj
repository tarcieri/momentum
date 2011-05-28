(ns picard.test.middleware.body-buffer
  (:use
   [clojure.test]
   [picard.api]
   [picard.test])
  (:require
   [picard.middleware :as middleware]))

;; TODO: not a good test
(deftest passes-simple-requests-through
  (with-app
    (-> (fn [downstream]
          (defupstream
            (request [_]
              (downstream :response [202 {"content-length" "0"}]))))
        middleware/body-buffer)
    (GET "/")
    (is (= 202 (last-response-status)))))

;; TODO: more tests
