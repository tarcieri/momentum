(ns picard.http.endpoint
  (:use
   picard.core
   picard.http.routing))

(defn respond
  [format content & opts]
  (when (not= :text format)
    (throw (Exception. "Unsupported format: " format)))

  (let [opts (apply hash-map opts)]
    [(opts :status 200) {} (buffer content)]))

(defn map-request
  [ch [hdrs body]]
  (cond
   (= body :chunked)
   (assoc hdrs :body (seq ch))

   :else
   (assoc hdrs :body body)))

(defn endpoint*
  [f]
  (fn [dn]
    (let [ch (channel)]
      (fn [evt val]
        (cond
         (= :request evt)
         (doasync (f (map-request ch val))
           #(dn :response %)
           (catch Exception err
             (dn :abort err)))

         (= :body evt)
         (if val
           (put ch val)
           (close ch)))))))
