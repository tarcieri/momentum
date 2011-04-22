(ns picard.utils
  (:import
   [java.util.concurrent
    Executors]))

(defn mk-thread-pool
  []
  (Executors/newCachedThreadPool))
