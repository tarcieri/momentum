(ns picard.test.middleware
  (:use
   [clojure.test]
   [lamina.core]
   [test-helper])
  (:require
   [picard.middleware :as middleware]))

(deftest hello-app-test
  (let [ch1 (channel)
        app (fn [downstream request]
              (downstream :response [200 {"content-length" "5"} "Hello"])
              (fn [_ _]))
        retried-app (middleware/retry app)]
    (retried-app
     (fn [evt val]
       (enqueue ch1 [evt val]))
     [{:path-info "/" :http-version [1 1] :request-method "GET" :script-info "/"}])
    (is (= (wait-for-message ch1 10)
           [:response [200 {"content-length" "5"} "Hello"]]))))

;; (deftest first-try-fails
;;   (let [ch1 (channel)
;;         latch (atom false)
;;         app (->
;;              (fn [downstream request]
;;                (if @latch
;;                  (downstream :response [200 {"content-length" "5"} "Hello"])
;;                  (do (reset! latch true)
;;                      (downstream :response [500 {"content-length" "6"} "error!"])))
;;                (fn [_ _]))
;;              middleware/retry)]
;;     (app
;;      #(enqueue ch1 [%1 %2])
;;      [{:path-info "/" :http-version [1 1] :request-method "GET" :script-info "/"}])

;;     (is (= (wait-for-message ch1 10)
;;            [:response [200 {"content-length" "5"} "Hello"]]))))
