(ns picard.test.http.aggregate
  (:use
   clojure.test
   picard.core.buffer
   picard.http.test)
  (:require
   [picard.http.aggregate :as aggregate]))

(defn- echo-app
  ([] (echo-app {}))
  ([opts]
     (aggregate/middleware
      (fn [dn]
        (fn [evt val]
          (cond
           (= :request evt)
           (dn :response (cons 200 val))

           :else
           (dn evt val))))
      opts)))

(deftest passes-simple-requests-through
  (with-app (echo-app {})
    (is (responded?
         (GET "/")
         [200 {"host"          "example.org"
               :http-version   [1 1]
               :query-string   ""
               :script-name    ""
               :request-method "GET"
               :path-info      "/"
               :remote-addr    ["127.0.0.1" 1234]
               :local-addr     ["127.0.0.1" 4321]} nil]))

    (POST "/" "ZOMG")
    (is (= (to-string (response-body))
           "ZOMG"))))

(deftest buffers-chunked-requests
  (with-app (echo-app {})
    (let [conn (GET "/" :chunked)]
      (conn :body "Hello")
      (conn :body "World")

      (is (nil? (response)))

      (conn :body nil)

      (is (= (to-string (response-body))
             "HelloWorld")))))
