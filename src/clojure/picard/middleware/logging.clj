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
               (swap! state #(merge hdrs %))))
           (next-up evt val))

         :downstream
         (fn [evt val]
           (cond
            (= :response evt)
            (let [status (response-status val)
                  chunk-size (body-size evt val)]
              (swap!
               state
               (fn [info]
                 (assoc info
                   :response-status status
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
                   (+ (info :response-body-size) chunk-size))))))
           (next-dn evt val))

         :finalize
         (fn [_] (.info logger @state))))))
