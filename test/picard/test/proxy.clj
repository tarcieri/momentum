(ns picard.test.proxy
  (:use
   [clojure.test]
   [picard.test]
   [lamina.core]
   [test-helper])
  (:require
   [picard.proxy :as prox]))

(defcoretest proxying-requests-through-the-proxy
  [ch1]
  :hello-world
  (with-app (prox/mk-proxy)
    (GET "/" {:remote-addr ["localhost" 1234]
              "connection" "close"
              "host"       "localhost:4040"})

    (is (next-msgs-for
         ch1
         :request [(includes-hdrs {"x-forwarded-for" "localhost"}) :dont-care]))

    (is (= (last-response-status)
           200))

    (is (= (last-response-headers)
           {:http-version    [1 1]
            "content-type"   "text/plain"
            "content-length" "5"
            "connection"     "close"}))

    (is (= (last-response-body)
           "Hello"))))

(deftest proxying-requests-to-invalid-host
  (with-app (prox/mk-proxy)
    (GET "/" {"host" "localhost:4040"})

    (is (= (last-response-status)
           502))

    (is (includes? {"content-length" "0"}
                   (last-response-headers)))))

(defcoretest appends-to-existing-x-forwarded-for-header
  [ch1]
  :hello-world
  (with-app (prox/mk-proxy)
    (GET "/" {"x-forwarded-for" "123.235.55.1"
              "host"            "localhost:4040"})

    (is (next-msgs-for
         ch1
         :request
         [(includes-hdrs {"x-forwarded-for" "123.235.55.1, localhost"})
          :dont-care]))))
