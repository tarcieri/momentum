(ns picard.core.deferred
  (:import
   [picard.core
    Channel
    Deferred
    DeferredSeq
    DeferredReceiver]))

(defprotocol DeferredValue
  (receive [_ success error]))

(extend-protocol DeferredValue
  Deferred
  (receive [dval success error]
    (doto dval
      (.receive
       (reify DeferredReceiver
         (success [_ val] (success val))
         (error   [_ err] (error err))))))

  DeferredSeq
  (receive [seq success error]
    (doto seq
      (.receive
       (reify DeferredReceiver
         (success [_ val] (success val))
         (error   [_ err] (error val))))))

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
  Channel
  (put   [ch val] (doto ch (.put val)))
  (abort [ch err] (doto ch (.abort err)))

  Deferred
  (put [dval val]   (doto dval (.put val)))
  (abort [dval err] (doto dval (.abort err))))

(defn deferred
  []
  (Deferred.))

(defn channel
  ([]           (Channel. 1 false))
  ([can-block?] (Channel. 1 can-block?)))
