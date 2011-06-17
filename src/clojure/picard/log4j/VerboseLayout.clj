(ns picard.log4j.VerboseLayout
  (:require
   [clojure.string :as str])
  (:import
   [org.apache.log4j.spi
    LoggingEvent])
  (:gen-class
   :name picard.log4j.VerboseLayout
   :extends org.apache.log4j.Layout))

(defn- format-state
  [state]
  (str
   (format "%20s :\n" "State")
   (str/join
    "\n"
    (map (fn [[k v]] (format "%20s : %s" (name k) v)) state))))

(defn- format-event
  [event]
  (format "%20s : %s\n" "Event" event))

(defn- pretty-format
  [evt msg]
  (str
   (msg :msg) ":\n"
   (when-let [event (msg :event)]
     (format-event event))
   (when-let [state (msg :state)]
     (str (format-state (dissoc state :pool :options)) "\n"))))

(defn -format
  [this ^LoggingEvent evt]
  (let [log-msg (.getMessage evt)]
    (str
     (.getLoggerName evt) " - "
     (if (and (map? log-msg) (log-msg :msg))
       (pretty-format evt log-msg)
       (str log-msg)))))

(defn -ignoresThrowable [_] true)
(defn -activateOptions [_])
