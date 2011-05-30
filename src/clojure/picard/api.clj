(ns picard.api
  (:import
   [org.jboss.netty.buffer
    ChannelBuffer
    ChannelBuffers]
   [org.jboss.netty.util
    HashedWheelTimer
    TimerTask]
   [java.nio.charset
    Charset]
   [java.util.concurrent
    TimeUnit]))

;; Conversions
(defn to-channel-buffer
  [str]
  (if (instance? ChannelBuffer str)
    str
    (ChannelBuffers/wrappedBuffer (.getBytes str))))

(defn response-status  [[status]]    status)
(defn response-headers [[_ headers]] headers)
(defn response-body    [[_ _ body]]  body)

(defmacro defupstream
  [& handlers]
  (let [evt (gensym) val (gensym)]
    `(fn [~evt ~val]
       ~(reduce
         (fn [else [evt* bindings & stmts]]
           (if (= :else evt*)
             `(let [~bindings [~evt ~val]] ~@stmts)
             `(if (= ~(keyword evt*) ~evt)
                (let [~bindings [~val]] ~@stmts)
                ~else)))
         nil (reverse handlers))
       true)))

(defn timer [] (HashedWheelTimer.))

(def global-timer (timer))

(defn timeout
  ([ms f] (timeout global-timer ms f))
  ([^HashedWheelTimer timer ms f]
     (.newTimeout timer
                  (reify TimerTask (run [_ _] (f)))
                  ms TimeUnit/MILLISECONDS)))
