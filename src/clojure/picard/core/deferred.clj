(ns picard.core.deferred
  (:import
   picard.core.DeferredState))

(defprotocol DeferredValue
  (receive [_ callback])
  (catch [_ klass callback])
  (catch-all [_ callback])
  (finally [_ callback])
  (wait-for [_ ms]))

(extend-protocol DeferredValue
  DeferredState
  (receive [dval callback]
    (.registerReceiveCallback dval callback))
  (catch [dval klass callback]
      (.registerCatchCallback dval klass callback))
  (catch-all [dval callback]
    (.registerCatchAllCallback dval callback))
  (finally [dval callback]
    (.registerFinallyCallback dval callback))
  (wait-for [dval ms]
    (.await dval (long ms)))

  Object
  (receive [o callback]
    (callback o o true))
  (catch [_ _ _])
  (catch-all [_ _])
  (finally [_ callback]
    (callback))
  (wait-for [_ _]
    true)

  nil
  (receive [_ callback]
    (callback nil nil true))
  (catch [_ _ _])
  (catch-all [_ _])
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
  (abort [dval err]
    (.abort dval err false)))

(defn wait
  ([dval] (wait dval 0))
  ([dval ms]
     (wait-for dval ms)))

(defn deferred
  []
  (DeferredState.))
