(ns picard.http.endpoint
  (:use
   picard.core
   picard.http.routing))

(defn map-request
  [ch [hdrs body]]
  (cond
   (= body :chunked)
   (assoc hdrs :body (seq ch))

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
  (fn [dn]
    (let [ch (channel)]
      (fn [evt val]
        (cond
         (= :request evt)
         (doasync (f (map-request ch val))
           (fn [[status hdrs body]]
             (if (coll? body)
               (do
                 (dn :response [status hdrs :chunked])
                 (handle-response-body dn body))
               (dn :response [status hdrs (buffer body)])))
           (catch Exception err
             (dn :abort err)))

         (= :body evt)
         (if val
           (put ch val)
           (close ch)))))))
