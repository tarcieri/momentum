(ns picard.core.deferred
  (:import
   [picard.core
    Deferred
    DeferredReceiver]))

(defprotocol DeferredValue
  (receive [_ success error]))

(extend-protocol DeferredValue
  Deferred
  (receive [dval success error]
    (.receive
     dval
     (reify DeferredReceiver
       (success [_ val] (success val))
       (error   [_ err] (error err))))
    dval)

  Object
  (receive [o success _]
    (success o)
    o)

  nil
  (receive [_ success _]
    (success nil)
    nil))

(defprotocol DeferredRealizer
  (put [_ v])
  (abort [_ err]))

(extend-protocol DeferredRealizer
  Deferred
  (put [dval val]
    (.put dval val)
    dval)
  (abort [dval err]
    (.abort dval err)
    dval))

(defn deferred
  []
  (Deferred.))
