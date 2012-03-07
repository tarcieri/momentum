(ns momentum.core.reactor
  (:import
   [momentum.net
    ReactorCluster
    Timeout]))

(def ^ReactorCluster reactors (ReactorCluster/getInstance))

(defn start-reactors
  [] (.start reactors))

(defn stop-reactors
  [] (.stop reactors))

(defn schedule-timeout
  [ms f]
  (let [timeout (Timeout. (reify Runnable (run [_] (f))))]
    (.scheduleTimeout reactors timeout ms)
    timeout))

(defn cancel-timeout
  [^Timeout timeout]
  (when timeout (.cancel timeout)))