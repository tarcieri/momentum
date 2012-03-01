(ns momentum.core.timer
  (:import
   [momentum.net
    ReactorCluster
    Timeout]))

(def ^ReactorCluster reactor-cluster (ReactorCluster/getInstance))

(defn register
  [ms f]
  (let [timeout (Timeout. (reify Runnable (run [_] (f))))]
    (.scheduleTimeout reactor-cluster timeout ms)
    timeout))

(defn cancel
  [^Timeout timeout]
  (when timeout
    (.cancel timeout)))
