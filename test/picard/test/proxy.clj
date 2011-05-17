(ns picard.test.proxy
  (:use
   [clojure.test]
   [lamina.core]
   [test-helper])
  (:require
   [picard.proxy :as prox]))

(defcoretest proxying-requests-through-the-proxy
  [_ ch2]
  :hello-world
  (let [prox (prox/mk-proxy)]
    (prox (fn [evt val] (enqueue ch2 [evt val]))
          [{:path-info      "/"
            :request-method "GET"
            "connection"    "close"
            "host"          "localhost:4040"}])

    (is (next-msgs-for
         ch2
         :response [200 {:http-version    [1 1]
                         "content-type"   "text/plain"
                         "content-length" "5"
                         "connection"     "close"} "Hello"]))))

(defcoretest proxying-requests-to-invalid-host
  [_ ch2]
  nil
  (let [prox (prox/mk-proxy)]
    (prox (fn [evt val] (enqueue ch2 [evt val]))
          [{:path-info      "/"
            :request-method "GET"
            "host"          "localhost:4040"}])

    (is (next-msgs-for
         ch2
         :response [502 {"content-length" "20"} "<h2>Bad Gateway</h2>"]))))
