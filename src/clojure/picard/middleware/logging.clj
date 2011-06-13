(ns picard.middleware.logging
  (:use
   [picard.helpers])
  (:import
   [java.util
    Date]
   [java.text
    SimpleDateFormat]
   [org.apache.log4j
    ConsoleAppender
    Logger
    Layout
    Priority
    SimpleLayout]
   [org.apache.log4j.spi
    LoggingEvent]))

(def commons-date-formatter (SimpleDateFormat. "dd/MMM/yyyy:kk:mm:ss Z"))

(defn format-commons-logging
  [request timestamp]
  (let [request-date (Date. (long timestamp))
        request-time-string (.format commons-date-formatter request-date)]
    (format "%s - - [%s] \"%s %s HTTP/%d.%d\" %d %d"
            (first (:remote-addr request ))
            request-time-string
            (:request-method request)
            (:path-info request)
            (first (:http-version request))
            (second (:http-version request))
            (:response-status request)
            (:response-body-size request))))

(def commons-logging-format-layout
  (proxy [Layout] []

    (format [^LoggingEvent logging-event]
      (format-commons-logging
       (.getMessage logging-event)
       (.getTimeStamp logging-event)))

    (ignoresThrowable [] true)))

(def default-options
  {:name     "request"
   :appender (ConsoleAppender. nil ConsoleAppender/SYSTEM_OUT)
   :layout   commons-logging-format-layout
   :priority Priority/INFO})

(defn- mk-logger
  [{name :name appender :appender layout :layout :as opts}]
  (let [^Logger logger (Logger/getLogger name)]
    (.setLayout appender layout)
    (.addAppender logger appender)
    logger))

(defn logging
  ([app] (logging app {}))
  ([app opts]
     (let [opts (merge default-options opts)
           ^Logger logger (mk-logger opts)]
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
         (fn [_] (.log logger (:priority opts) @state))))))
