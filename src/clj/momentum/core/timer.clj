(ns momentum.core.timer
  (:import
   [momentum.net
    ReactorCluster
    Timeout]))

(def ^ReactorCluster reactor-cluster (ReactorCluster/getInstance))

(defn register
  [ms f]
  (.scheduleTimeout
   reactor-cluster
   (Timeout.
    (reify Runnable
      (run [_] (f))))
   ms))

(defn cancel
  [^Timeout timeout]
  (when timeout
    (.cancel timeout)))
