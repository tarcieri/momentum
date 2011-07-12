(ns picard.test.middleware.json
  (:use
   [clojure.test]
   [picard.helpers]
   [picard.test])
  (:require
   [picard.middleware :as middleware]))

(defn- handle-request
  [{path-info :path-info :as hdrs} body]
  (cond
   (= path-info "/simple-request")
   [200 {:request-body body} ""]

   (= path-info "/simple-response")
   [200 {"content-type" "application/json"} {:hello 1}]))

(defn- test-stack
  [dn]
  (defstream
    (request [[hdrs body]]
      (dn :response (handle-request hdrs body)))))

(defmacro with-json-stack
  [_ opts & stmts]
  `(with-app (middleware/json test-stack ~opts) ~@stmts))

(deftest decodes-a-simple-request
  (with-json-stack
    :options {}
    (POST "/simple-request"
          {"content-type" "application/json"}
          "{\"hello\": 1}")

    (is (= {"hello" 1} (last-response-headers :request-body)))

    (POST "/simple-request"
          {"content-type" "APPLICATION/JSON"}
          "{\"hello\": 1}")

    (is (= {"hello" 1} (last-response-headers :request-body)))

    (POST "/simple-request"
          {"content-type" "application/json; charset=utf-8"}
          "{\"hello\": 1}")

    (is (= {"hello" 1} (last-response-headers :request-body)))))

(deftest doesnt-decode-if-no-content-type
  (with-json-stack
    :options {}
    (POST "/simple-request"
          {} "{\"hello\": 1}")

    (is (= "{\"hello\": 1}" (last-response-headers :request-body)))))

(deftest encodes-a-simple-response
  (with-json-stack
    :options {}
    (GET "/simple-response")
    (is (= "{\"hello\":1}" (last-response-body)))))
