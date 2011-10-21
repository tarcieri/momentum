(ns picard.core.deferred
  (:import
   picard.core.DeferredState))

(defprotocol DeferredValue
  (receive
    [_ callback])
  (catch*
    [_ klass callback])
  (finally*
    [_ callback])
  (catch-all
    [_ callback]))

(extend-protocol DeferredValue
  DeferredState
  (receive [dval callback]
    (.registerReceiveCallback dval callback)
    dval)
  (catch* [dval klass callback]
    (.registerCatchCallback dval klass callback)
    dval)
  (finally* [dval callback]
    (.registerFinallyCallback dval callback)
    dval)
  (catch-all [dval callback]
    (.registerCatchAllCallback dval callback)
    dval)

  Object
  (receive [o callback]
    (callback o)
    o)
  (catch* [o _ _]
    o)
  (finally* [o callback]
    (callback)
    o)
  (catch-all [o _]
    o)

  nil
  (receive [_ callback]
    (callback nil)
    nil)
  (catch* [_ _ _]
    nil)
  (finally* [_ callback]
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
    (.abort dval err)
    dval))

(defn deferred
  []
  (DeferredState.))
