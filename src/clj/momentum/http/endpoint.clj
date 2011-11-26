(ns momentum.http.endpoint
  (:use
   momentum.core
   momentum.http.routing)
  (:require
   [momentum.http.websocket :as websocket]))

(defn map-request
  [ch hdrs body]
  (cond
   (#{:chunked :upgraded} body)
   (assoc hdrs :input (seq ch))

   :else
   (assoc hdrs :body body)))

(defn- handle-response-body
  [dn body]
  (doasync body
    (fn [[chunk & more]]
      (dn :body (buffer chunk))
      (when chunk
        (recur* more)))))

(defn endpoint*
  [f]
  (fn [dn _]
    (let [ch (channel)]
      (fn [evt val]
        (cond
         (= :request evt)
         (let [[hdrs req-body] val]
           (doasync (f (map-request ch hdrs req-body))
             (fn [[status hdrs resp-body]]
               (if (coll? resp-body)
                 (let [type (if (= 101 status) :upgraded :chunked)]
                   (dn :response [status hdrs type])
                   (handle-response-body dn resp-body))
                 (dn :response [status hdrs (buffer resp-body)])))
             (catch Exception err
               (dn :abort err))))

         (= :body evt)
         (if val
           (put ch val)
           (close ch)))))))

(defmacro endpoint
  [& routes]
  `(websocket/proto
    (routing
     ~@(map
        (fn [[method path binding & stmts]]
          (let [expr `(endpoint* (fn ~binding ~@stmts))]
            (if (= method 'ANY)
              `(match ~path ~expr)
              `(match ~(keyword method) ~path ~expr))))
        routes))))
