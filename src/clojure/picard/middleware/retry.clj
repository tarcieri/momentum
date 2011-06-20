(ns picard.middleware.retry
  (:require [clojure.contrib.logging :as log])
  (:use [picard helpers utils]))

(defrecord State [app upstream retries sent-body? opts])

(def retry-codes     #{408 500 502 503 504})
(def default-checker #(not (contains? retry-codes (response-status %))))
(def default-options {:retries [1000 2000 4000 8000 16000 32000]
                      :validate-response-with default-checker})

(defn- initial-state
  [app opts]
  (let [opts (merge default-options opts)]
    (State. app nil (:retries opts) false opts)))

(declare retry-request)

(defn- attempt-request
  [state downstream request current-state]
  (let [current-downstream-atom (atom downstream)]
   (with
    ((.app current-state)
     (fn [evt val]
       (let [current-state @state
             current-downstream @current-downstream-atom]
         (if (and (= :response evt)
                  (not ((-> current-state .opts :validate-response-with) val))
                  (not (.sent-body? current-state))
                  (not (empty? (.retries current-state))))
           (swap-then!
            state
            #(assoc % :retries (rest (.retries %)))
            (fn [current-state*]

              (reset! current-downstream-atom nil)

              ;; abort current upstream
              (try
                ((:upstream current-state) :abort (Exception. "retry handler failed"))
                (catch Exception _))

              (let [retry (first (.retries current-state))]
                (if (< 0 retry)
                  (timeout
                   (first (.retries current-state))
                   #(retry-request state downstream request current-state*))
                  (retry-request state downstream request current-state*)))))

           (when-let [current-downstream @current-downstream-atom]
             (current-downstream evt val))
           ))))
    :as upstream
    (swap! state #(assoc % :upstream upstream))
    (upstream :request request))))

(defn- retry-request
  [state downstream request current-state]
  (log/info (str "retrying request: " (request-url request)))
  (attempt-request state downstream request current-state))

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
