(ns picard.middleware.logging
  (:use
   [picard.helpers])
  (:import
   [org.apache.log4j
    Logger]
   [org.apache.log4j.spi
    LoggingEvent]
   [picard.log4j
    CommonLogFormatLayout]))

(defn logging
  ([app]
     (let [^Logger logger (Logger/getLogger "requestLogger")]
       (defmiddleware [state next-dn next-up]
         app

         :initialize
         (swap! state #(assoc % :response-body-size 0))

         :upstream
         (fn [evt val]
           (when (= :request evt)
             (let [hdrs (first val)]
               (swap!
                state
                #(assoc %
                   :remote-addr    (hdrs :remote-addr)
                   :request-method (hdrs :request-method)
                   :path-info      (hdrs :path-info)
                   :http-version   (hdrs :http-version)))))
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
