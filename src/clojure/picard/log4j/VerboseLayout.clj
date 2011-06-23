(ns picard.log4j.VerboseLayout
  (:require
   [clojure.string :as str])
  (:import
   [org.apache.log4j.spi
    LoggingEvent])
  (:gen-class
   :name picard.log4j.VerboseLayout
   :extends org.apache.log4j.Layout))

(defn- format-value
  [val]
  (str/join
   "\n                       " ;; lol
   (map
    #(str/trim (str/join (map str %)))
    (partition 120 120 "" (str (or val "nil"))))))

(defn- format-state
  [state]
  (str
   (format "%20s :\n" "State")
   (str/join
    "\n"
    (map (fn [[k v]]
           (format "%20s : %s"
                   (name k) (format-value v)))
         state))))

(defn- format-event
  [event]
  (str (format "%20s : " "Event") (format-value event) "\n"))

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
     (str/upper-case (.getLoggerName evt)) " - "
     (if (and (map? log-msg) (log-msg :msg))
       (pretty-format evt log-msg)
       (str log-msg "\n")))))

(defn -ignoresThrowable [_] true)
(defn -activateOptions [_])
