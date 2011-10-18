(ns support.queue
  (:refer-clojure :exclude [peek])
  (:import
   [java.util.concurrent
    LinkedBlockingQueue
    TimeUnit]))

(defn channel
  []
  (LinkedBlockingQueue.))

(defn enqueue
  [queue & msgs]
  (doseq [msg msgs]
    (.put queue msg))
  queue)

(defn poll
  [queue timeout]
  (let [v (.poll queue timeout TimeUnit/MILLISECONDS)]
    (when-not v
      (throw (Exception. "Queue did not produce")))
    v))

(defn peek
  [queue]
  (.peek queue))

;; Ghetto channel receive
(defn receive
  [queue f]
  (future
    (when-let [v (.poll queue 4000 TimeUnit/MILLISECONDS)]
      (f v))))
