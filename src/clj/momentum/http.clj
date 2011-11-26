(ns momentum.http
  (:use momentum.util.namespace)
  (:require
   [momentum.http.endpoint  :as endpoint]
   [momentum.http.response  :as response]))

(import-fn #'response/respond)
(import-macro #'endpoint/endpoint)

(defn websocket?
  [request]
  (= "websocket" (request "upgrade")))
