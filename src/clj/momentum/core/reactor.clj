(ns momentum.core.reactor
  (:import
   [momentum.reactor
    Reactor
    ReactorCluster
    Timeout]))

(def ^ReactorCluster reactors (ReactorCluster/getInstance))

(defn start-reactors
  [] (.start reactors))

(defn stop-reactors
  [] (.stop reactors))

(defn preschedule
  "Takes a function f and returns a fn that takes the same number of
  arguments that has been assigned to the current reactor thread. When
  the returned function is called, f will be called on the reactor
  thread and passed the arguments."
  [f]
  (let [reactor (.currentReactor reactors)]
    (fn [& args] (.schedule reactor #(apply f args)))))

(defn schedule
  "Runs a given function on the current reactor. If not on a reactor
  thread, the least busy reactor will be selected."
  [f]
  (.schedule reactors f))

(defn schedule-timeout
  [ms f]
  (let [timeout (Timeout. (reify Runnable (run [_] (f))))]
    (.scheduleTimeout reactors timeout ms)
    timeout))

(defn cancel-timeout
  [^Timeout timeout]
  (when timeout (.cancel timeout)))