(ns support.queue
  (:refer-clojure :exclude [peek])
  (:import
   [java.util.concurrent
    LinkedBlockingQueue
    TimeoutException
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
    (cond
     (not v)
     (throw (TimeoutException. "Queue did not produce"))

     (= ::close v)
     (throw (Exception. "Queue is closed"))

     :else
     v)))

(defn peek
  [queue]
  (let [v (.peek queue)]
    (when-not (= ::close v)
      v)))

;; Ghetto channel receive
(defn receive
  [queue f]
  (future
    (when-let [v (.poll queue 4000 TimeUnit/MILLISECONDS)]
      (when-not (= ::close v)
        (try (f v)
             (catch Exception err
               (.printStackTrace err)))))))

(defn receive-all
  [queue f]
  (future
    (loop []
      (when-let [v (.poll queue 4000 TimeUnit/MILLISECONDS)]
        (when-not (= ::close v)
          (try (f v)
               (catch Exception err
                 (.printStackTrace err)))
          (recur))))))

(defn close
  [queue]
  (.put queue ::close))
