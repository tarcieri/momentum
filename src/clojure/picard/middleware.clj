(ns picard.middleware
  (:use
   [picard.api]
   [picard.utils]))

(defrecord State [app upstream attempts sent-body? opts])

(def retry-codes #{408 500 502 503 504})
(def default-options {:retries [0.2 0.4 0.8 1.6]})

(defn- initial-state
  [app opts]
  (State. app nil 0 false opts))

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
                 (contains? retry-codes (response-status val)))
          (attempt-request state downstream request current-state)
          (downstream evt val)))))
   :as upstream
   (swap! state #(assoc % :upstream upstream))
   (upstream :request request)
   upstream))

(defn retry
  [app & opts]
  (fn [downstream]
    (let [state (atom (initial-state app opts))]
      (fn [evt val]
        (let [current-state @state]
          (if (= :request evt)
            (attempt-request state downstream val current-state)
            ((.upstream current-state) evt val)))))))
