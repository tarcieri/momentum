(ns picard.middleware.logging
  (:use
   [picard.helpers])
  (:import
   [org.apache.log4j
    Logger]
   [org.apache.log4j.spi
    LoggingEvent]))

(def default-opts { :logger-name "picard.request" })

(defn logging
  ([app] (logging app {}))
  ([app opts]
     (let [opts (merge default-opts opts)
           ^Logger logger (Logger/getLogger (:logger-name opts))]
       (defmiddleware [state next-dn next-up]
         app

         :initialize
         (swap! state #(assoc % :response-body-size 0))

         :upstream
         (fn [evt val]
           (when (= :request evt)
             (let [hdrs (first val)]
               (swap! state #(-> hdrs
                                 (merge %)
                                 (assoc :request-start-time (System/currentTimeMillis))))))
           (next-up evt val))

         :downstream
         (fn [evt val]
           (cond
            (= :response evt)
            (let [[status hdrs body :as response] val
                  status (response-status response)
                  content-type (content-type hdrs)
                  chunk-size (body-size evt val)]
              (swap!
               state
               (fn [info]
                 (assoc info
                   :response-status status
                   :content-type content-type
                   :response-body-size
                   (+ (info :response-body-size)
                      chunk-size)))))

            (= :body evt)
            (let [chunk-size (body-size evt val)]
              (swap!
               state
               (fn [info]
                 (assoc info
                   :response-body-size
                   (+ (info :response-body-size) chunk-size)))))

            (= :done evt)
            (swap!
             state
             (fn [info]
               (assoc info
                 :response-time-ms
                 (- (System/currentTimeMillis) )))))

           (next-dn evt val))

         :finalize
         (fn [_]
           (let [current-state @state
                 current-time (System/currentTimeMillis)
                 response-time-ms (- current-time (:request-start-time current-state))]
             (.info logger (assoc current-state :response-time-ms response-time-ms))))
         ))))
