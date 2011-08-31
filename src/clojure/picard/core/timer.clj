(ns picard.core.timer
  (:import
   [org.jboss.netty.util
    HashedWheelTimer
    Timeout
    TimerTask]
   [java.util.concurrent
    TimeUnit]))

(defn mk-timer
  ([] (mk-timer 100))
  ([ms]
     (HashedWheelTimer. ms TimeUnit/MILLISECONDS)))

(def global-timer (mk-timer 1000))

(defn- callbackify
  [f]
  (reify TimerTask
    (run [_ timeout]
      (when-not (.isCancelled timeout)
        (f)))))

(defn register
  ([ms f] (register global-timer ms f))
  ([timer ms f]
     (let [callback (callbackify f)]
       (.newTimeout timer callback ms TimeUnit/MILLISECONDS))))

(defn cancel
  [timeout]
  (.cancel timeout))
