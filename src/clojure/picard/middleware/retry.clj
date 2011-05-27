(ns picard.middleware.retry
  (:use [picard api utils]))

(defrecord State [app upstream retries sent-body? opts])

(def retry-codes     #{408 500 502 503 504})
(def default-checker #(not (contains? retry-codes (response-status %))))
(def default-options {:retries [1000 2000 4000 8000 16000 32000]
                      :validate-response-with default-checker})

(defn- initial-state
  [app opts]
  (let [opts (merge default-options opts)]
    (State. app nil (:retries opts) false opts)))

(defn- mk-downstream
  [downstream state]
  (fn [evt val]
    (downstream evt val)))

(defn- attempt-request
  [state downstream request current-state]
  (with
   ((.app current-state)
    (fn [evt val]
      (let [current-state @state]
        ;; TODO: Switch this to a custom check
        (if (and (= :response evt)
                 (not ((-> current-state .opts :validate-response-with) val))
                 (not (.sent-body? current-state))
                 (not (empty? (.retries current-state))))
          (swap-then!
           state
           #(assoc % :retries (rest (.retries %)))
           (fn [current-state]
             (attempt-request state downstream request current-state)))
          (downstream evt val)))))
   :as upstream
   (swap! state #(assoc % :upstream upstream))
   (upstream :request request)
   upstream))

(defn retry
  ([app] (retry app {}))
  ([app opts]
     (fn [downstream]
       (let [state (atom (initial-state app opts))]
         (fn [evt val]
           (let [current-state @state]
             (if (= :request evt)
               (attempt-request state downstream val current-state)
               (do (when (and (= :body evt) (not (.sent-body? current-state)))
                     (swap! state #(assoc % :sent-body? true)))
                   ((.upstream current-state) evt val)))))))))
