(ns picard.log4j.CommonLogFormatLayout
  (:import
   [java.util
    Date]
   [java.text
    SimpleDateFormat]
   [org.apache.log4j
    Level
    Logger
    Layout]
   [org.apache.log4j.spi
    LoggingEvent])
  (:gen-class
   :extends org.apache.log4j.Layout))

(def commons-date-formatter (SimpleDateFormat. "dd/MMM/yyyy:kk:mm:ss Z"))

(defn format-commons-logging
  [request timestamp]
  (let [request-date (Date. (long timestamp))
        request-time-string (.format commons-date-formatter request-date)]
    (format "%s - - [%s] \"%s %s HTTP/%d.%d\" %d %d\n"
            (first (:remote-addr request ))
            request-time-string
            (:request-method request)
            (:path-info request)
            (first (:http-version request))
            (second (:http-version request))
            (:response-status request)
            (:response-body-size request))))

(defn -format
  [this ^LoggingEvent logging-event]
  (format-commons-logging
   (.getMessage logging-event)
   (.getTimeStamp logging-event)))

(defn -ignoresThrowable [_] true)
(defn -activateOptions [_])
