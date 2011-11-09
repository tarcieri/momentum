(ns momentum.http
  (:require
   [momentum.http.endpoint  :as endpoint]
   [momentum.http.response  :as response]
   [momentum.http.routing   :as routing]
   [momentum.http.websocket :as websocket]))

(def respond response/respond)

(defn websocket?
  [request]
  (= "websocket" (request "upgrade")))

(defmacro endpoint
  [& routes]
  `(websocket/proto
    (routing/routing
     ~@(map
        (fn [[method path binding & stmts]]
          (let [expr `(endpoint/endpoint* (fn ~binding ~@stmts))]
            (if (= method 'ANY)
              `(routing/match ~path ~expr)
              `(routing/match ~(keyword method) ~path ~expr))))
        routes))))
