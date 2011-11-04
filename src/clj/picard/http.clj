(ns picard.http
  (:require
   [picard.http.endpoint :as endpoint]
   [picard.http.routing  :as routing]))

(def respond endpoint/respond)

(defmacro endpoint
  [& routes]
  `(routing/routing
    ~@(map
       (fn [[method path binding & stmts]]
         (let [expr `(endpoint/endpoint* (fn ~binding ~@stmts))]
           (if (= method 'ANY)
             `(routing/match ~path ~expr)
             `(routing/match ~(keyword method) ~path ~expr))))
       routes)))
