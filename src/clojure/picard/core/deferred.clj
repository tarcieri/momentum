(ns picard.core.deferred)

(defprotocol DeferredValue
  (receive [_ callback]))

(extend-type Object
  DeferredValue
  (receive [o callback]
    (callback o o true)))

(extend-type nil
  DeferredValue
  (receive [_ callback]
    (callback nil nil true)))
