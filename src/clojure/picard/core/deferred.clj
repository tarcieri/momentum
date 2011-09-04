(ns picard.core.deferred
  (:import
   picard.core.DeferredState))

(defprotocol DeferredValue
  (receive [_ callback])
  (catch [_ klass callback])
  (finally [_ callback])
  (wait-for [_ ms]))

(extend-protocol DeferredValue
  DeferredState
  (receive [dval callback]
    (.registerReceiveCallback dval callback))

  (catch [dval klass callback])
  (finally [dval callback])
  (wait-for [dval ms]
    (.await dval (long ms)))

  Object
  (receive [o callback]
    (callback o o true))
  (catch [_ _ _])
  (finally [_ callback]
    (callback))
  (wait-for [_ _]
    true)

  nil
  (receive [_ callback]
    (callback nil nil true))
  (catch [_ _ _])
  (finally [_ callback]
    (callback))
  (wait-for [_ _]
    true))

(defprotocol DeferredRealizer
  (put [_ v])
  (abort [_ err]))

(extend-protocol DeferredRealizer
  DeferredState
  (put [dval val]
    (.realize dval val))
  (abort [dvak err]
    (throw (Exception. "Not implemented"))))

(defn wait
  ([dval] (wait dval 0))
  ([dval ms]
     (wait-for dval ms)))

(defn deferred
  []
  (DeferredState.))
