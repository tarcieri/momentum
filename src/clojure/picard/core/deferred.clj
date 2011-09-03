(ns picard.core.deferred
  (:import
   picard.core.DeferredState))

(defprotocol DeferredValue
  (receive [_ callback])
  (wait-for [_ ms]))

(extend-protocol DeferredValue
  DeferredState
  (receive [dval callback]
    (.registerReceiveCallback
     dval callback
     (fn [val]
       (callback dval val true))))
  (wait-for [dval ms]
    (.await dval (long ms)))

  Object
  (receive [o callback]
    (callback o o true))
  (wait-for [_ _]
    true)

  nil
  (receive [_ callback]
    (callback nil nil true))
  (wait-for [_ _]
    true))

(defprotocol DeferredRealizer
  (put [_ v]))

(extend-protocol DeferredRealizer
  DeferredState
  (put [dval val]
    (.realize dval val)))

(defn wait
  ([dval] (wait dval 0))
  ([dval ms]
     (wait-for dval ms)))

(defn deferred
  []
  (DeferredState.))
