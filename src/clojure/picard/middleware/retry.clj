(ns picard.middleware.retry
  (:require [clojure.contrib.logging :as log])
  (:use [picard helpers utils]))

;; Use a record for the perfz
(defrecord State [app upstream retries sent-body? opts retry-timeout aborting?])

(defn- chunked?
  [[_ body]]
  (= :chunked body))

(def retry-codes     #{408 500 502 503 504})
(def default-checker #(not (contains? retry-codes (response-status %))))
(def default-options {:retries [1000 2000 4000 8000 16000 32000]
                      :validate-response-with default-checker})

(defn- initial-state
  [app opts]
  (let [opts (merge default-options opts)]
    (State. app nil (:retries opts) false opts nil false)))

(declare retry-request)

(defn- should-retry?
  [request evt val current-state]
  (and (= :response evt)
       (not (chunked? request))
       (not ((-> current-state .opts :validate-response-with) val))
       (not (.sent-body? current-state))
       (not (.aborting? current-state))
       (not (empty? (.retries current-state)))))

(defn- mk-downstream
  [state downstream request]
  (let [current-downstream-atom (atom downstream)]
    (fn [evt val]
      (let [current-state      @state
            current-downstream @current-downstream-atom
            retry-timeout-ms   (first (.retries current-state))
            upstream           (.upstream current-state)]
        (if (should-retry? request evt val current-state)

          ;; should retry
          (swap-then!
           state
           ;; this clears the upstream and removes a retry
           #(assoc % :retries (rest (.retries %)) :upstream nil)
           (fn [current-state]
             ;; this is to prevent us sending downstream events
             ;; after one of the retry requests has failed
             (reset! current-downstream-atom nil)

             ;; abort current upstream
             (try
               (upstream :abort (Exception. "retry handler failed"))
               (catch Exception _))

             ;; if we have another timeout to process then set it up
             ;; and save it in the state so that it can be canceled if
             ;; neeed be
             (if (< 0 retry-timeout-ms)
               ;; retry should take place in some amount of time
               (let [retry-timeout
                     (timeout
                      retry-timeout-ms
                      #(retry-request state downstream request))]
                 (swap! state #(assoc % :retry-timeout retry-timeout)))

               ;; retry of zero indicates retry immediately
               (retry-request state downstream request))))

          ;; shouldn't retry
          (when-let [current-downstream @current-downstream-atom]
            (current-downstream evt val)))))))

(defn- attempt-request
  [state downstream request]
  (let [app      (.app @state)
        upstream (app (mk-downstream state downstream request))]
    (swap! state #(assoc % :upstream upstream))
    (upstream :request request)))

(defn- retry-request
  [state downstream [hdrs :as request]]
  (when-not (.aborting? @state)
    (log/info (str "retrying request: " (request-url hdrs)))
    (attempt-request state downstream request)))

(defn retry
  ([app] (retry app {}))
  ([app opts]
     (fn [downstream]
       (let [state (atom (initial-state app opts))]

         (defstream
           (request [request]
             (attempt-request state downstream request))

           (body [val]
             (swap! state #(assoc % :sent-body? true))
             (if-let [upstream (.upstream @state)]
               (upstream :body val)))

           (abort [err]
             (swap! state #(assoc % :aborting true))
             (when-let [retry-timeout (.retry-timeout @state)]
               (.cancel retry-timeout))
             (when-let [upstream (.upstream @state)]
               (upstream :abort err)))

           (else [evt val]
             (if-let [upstream (.upstream @state)]
               (upstream evt val))))))))
