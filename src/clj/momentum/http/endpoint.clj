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

(defn- handle-request
  [f dn [hdrs body] ch up]
  (try
    (doasync (f (map-request ch hdrs body))
      (fn [[status hdrs body]]
        (if (coll? body)
          (let [type (if (= 101 status) :upgraded :chunked)]
            (dn :response [status hdrs type])
            (reset! up (sink dn body)))
          (dn :response [status hdrs (buffer body)])))
      (catch Exception e
        (dn :abort e)))
    (catch Exception e
      (dn :abort e))))

(defn endpoint*
  [f]
  (fn [dn _]
    (let [ch (channel dn 5)
          up (atom nil)]
      (fn [evt val]
        (cond
         (= :request evt)
         (handle-request f dn val ch up)

         (= :body evt)
         (if val
           (put ch val)
           (close ch))

         (#{:pause :resume} evt)
         (when-let [upstream @up]
           (upstream evt val)))))))

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
