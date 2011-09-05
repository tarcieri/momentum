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
    (.registerReceiveCallback dval callback)
    dval)
  (rescue [dval klass callback]
    (.registerRescueCallback dval klass callback)
    dval)
  (finalize [dval callback]
    (.registerFinalizeCallback dval callback)
    dval)
  (catch-all [dval callback]
    (.registerCatchAllCallback dval callback)
    dval)

  Object
  (receive [o callback]
    (callback o o true)
    o)
  (rescue [o _ _]
    o)
  (finalize [o callback]
    (callback)
    o)
  (catch-all [o _]
    o)

  nil
  (receive [_ callback]
    (callback nil nil true)
    nil)
  (rescue [_ _ _]
    nil)
  (finalize [_ callback]
    (callback)
    nil)
  (catch-all [_ _]
    nil))

(defprotocol DeferredRealizer
  (put [_ v])
  (abort [_ err]))

(extend-protocol DeferredRealizer
  DeferredState
  (put [dval val]
    (.realize dval val)
    dval)
  (abort [dval err]
    (.abort dval err false)
    dval))

(defn wait
  ([dval] (wait dval 0))
  ([^DeferredState dval ms]
     (.await dval (long ms))))

(defn deferred
  []
  (DeferredState.))
