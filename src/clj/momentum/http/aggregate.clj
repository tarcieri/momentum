(ns momentum.http.aggregate
  (:use
   momentum.core
   momentum.utils.core))

(def request-entity-too-large
  [413 {"content-length" "0"} nil])

(def server-error
  [500 {"content-length" "0"} nil])

(defn- mk-initial-state
  [dn err f]
  {:success-fn f
   :error-fn   #(dn :response err)
   :size       0
   :queue      clojure.lang.PersistentQueue/EMPTY
   :aborted?   nil})

(defn- handle-chunk
  [state chunk hard soft]
  (let [chunk-size (remaining chunk)
        {error     :error-fn
         aborted?  :aborted?}
        (swap!
         state
         (fn [{size :size queue :queue :as current-state}]
           (let [size (+ size chunk-size)]
             (assoc current-state
               :size     size
               :aborted? (< hard size)
               :queue    (conj queue chunk)))))]

    (when aborted? (error))))

(defn- aggregator
  [stream dn buffer? hard soft]
  (if-not buffer?
    stream
    (let [msg (atom nil)]
      (fn [evt val]
        (cond
         (= :request evt)
         (let [[hdrs body] val]
           (if (not= :chunked body)
             (stream :request val)
             (reset!
              msg
              (mk-initial-state
               dn request-entity-too-large
               #(stream :request [hdrs %])))))

         (= :response evt)
         (let [[status hdrs body] val]
           (if (not= :chunked body)
             (stream :response val)
             (reset!
              msg
              (mk-initial-state
               dn server-error
               #(stream :response [status hdrs %])))))

         (= :body evt)
         (let [{success  :success-fn
                aborted? :aborted?
                queue    :queue
                :as       curr} @msg]

           (when-not aborted?
             (if val
               (handle-chunk msg (buffer val) hard soft)
               (success (buffer queue)))))

         :else
         (stream evt val))))))

(def default-opts
  {:upstream   true
   :downstream true
   :hard-limit (MB 50)
   :soft-limit nil})

(defn- merge-defaults
  [opts]
  (if-let [limit (opts :limit)]
    (merge default-opts opts {:hard-limit limit})
    (merge default-opts opts)))

(defn middleware
  ([app] (middleware app {}))
  ([app opts]
     (let [opts        (merge-defaults opts)
           upstream?   (opts :upstream)
           downstream? (opts :downstream)
           hard-limit  (opts :hard-limit)
           soft-limit  (opts :soft-limit)]
       (fn [dn env]
        (aggregator
         (app (aggregator dn dn downstream? hard-limit soft-limit) env)
         dn upstream? hard-limit soft-limit)))))
