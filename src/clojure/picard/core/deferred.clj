(ns picard.core.deferred
  (:import
   picard.core.DeferredState))

(defprotocol DeferredValue
  (receive
    [_ callback])
  (rescue
    [_ klass callback])
  (finalize
    [_ callback])
  (catch-all
    [_ callback]))

(extend-protocol DeferredValue
  DeferredState
  (receive [dval callback]
    (.registerReceiveCallback dval callback))
  (rescue [dval klass callback]
    (.registerRescueCallback dval klass callback))
  (finalize [dval callback]
    (.registerFinalizeCallback dval callback))
  (catch-all [dval callback]
    (.registerCatchAllCallback dval callback))

  Object
  (receive [o callback]
    (callback o o true))
  (rescue [_ _ _])
  (finalize [_ callback]
    (callback))
  (catch-all [_ _])

  nil
  (receive [_ callback]
    (callback nil nil true))
  (rescue [_ _ _])
  (finalize [_ callback]
    (callback))
  (catch-all [_ _]))

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
  ([^DeferredState dval ms]
     (.await dval (long ms))))

(defn deferred
  []
  (DeferredState.))
