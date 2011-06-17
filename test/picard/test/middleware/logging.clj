(ns picard.test.middleware.logging
  (:use
   [clojure.test]
   [picard.helpers]
   [picard.test])
  (:require
   [picard.middleware :as middleware])
  (:import
   [java.util
    Date]
   [java.text
    SimpleDateFormat]
   [org.apache.log4j
    AppenderSkeleton
    Level
    Layout
    Logger
    SimpleLayout]
   [org.apache.log4j.spi
    LoggingEvent]
   [picard.log4j
    CommonLogFormatLayout]))

(declare *log-msgs*)

(defn now [] (System/currentTimeMillis))

(def commons-date-formatter (SimpleDateFormat. "dd/MMM/yyyy:kk:mm:ss Z"))

(defn- format-commons-logging
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

(defn- mock-appender
  []
  (if-let [msgs *log-msgs*]
    (proxy [AppenderSkeleton] []
      (append [^LoggingEvent evt]
        (let [msg (.. this getLayout (format evt))]
          (swap! msgs #(conj % msg))))
      (close []))
    (throw (Exception. "Not in the right context"))))

(defn- log-msgs
  []
  (when-not *log-msgs*
    (throw (Exception. "Not in correct binding")))
  @*log-msgs*)

(defn- hello-world-app
  [dn]
  (fn [evt val]
    (when (= :request evt)
      (dn :response [200 {"content-length" "5"} "Hello"]))))

(def default-request
  {:remote-addr        ["127.0.0.1" 1234]
   :request-method     "GET"
   :path-info          "/"
   :http-version       [1 1]
   :response-status    200
   :response-body-size 5})

(deftest logs-simple-exchanges
  (with-app (middleware/logging hello-world-app)
    (GET "/")
    (POST "/foo")

    (is (= 200 (last-response-status)))
    (is (= (log-msgs)
           [(format-commons-logging
             default-request (now))
            (format-commons-logging
             (assoc default-request
               :request-method "POST"
               :path-info      "/foo") (now))]))))

(deftest logs-the-response-status
  (with-app
    (build-stack
     (middleware/logging)
     (fn [dn]
       (defstream
         (request []
                  (dn :response [302 {"content-length" "0"} ""])))))

    (GET "/")
    (is (= 302 (last-response-status)))
    (is (= (log-msgs)
           [(format-commons-logging
             (assoc default-request
               :response-status    302
               :response-body-size 0) (now))]))))

(deftest logs-chunked-response
  (with-app
    (build-stack
     (middleware/logging)
     (fn [dn]
       (defstream
         (request []
                  (dn :response [200 {"transfer-encoding" "chunked"} :chunked])
                  (dn :body "Hello")
                  (dn :body "World")
                  (dn :body nil)))))

    (GET "/")
    (is (= 200 (last-response-status)))
    (is (= (log-msgs)
           [(format-commons-logging
             (assoc default-request
               :response-body-size 10) (now))]))))

(deftest tracks-the-remote-ip
  (with-app (middleware/logging hello-world-app)
    (GET "/" {:remote-addr ["12.34.56.78" 1234]})

    (is (= 200 (last-response-status)))
    (is (= (log-msgs)
           [(format-commons-logging
             (assoc default-request
               :remote-addr ["12.34.56.78" 1234]) (now))]))))

(defn- setup-logger
  [f]
  (binding [*log-msgs* (atom [])]
    (let [logger (Logger/getRootLogger)
          appender (mock-appender)]
      (.removeAllAppenders logger)
      (.addAppender logger appender)
      (.setLayout appender (CommonLogFormatLayout.)))
    (f)))

(use-fixtures :each setup-logger)
