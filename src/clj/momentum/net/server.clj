(ns momentum.net.server
  (:use
   momentum.core
   momentum.net.core))

(def default-options
  {:port 4040})

(defn start
  ([app]      (start app {}))
  ([app opts] (start-tcp-server app (merge default-options opts))))

(defn stop [f] (f))
