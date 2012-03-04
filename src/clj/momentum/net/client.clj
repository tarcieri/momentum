(ns momentum.net.client
  (:use momentum.net.core))

(defn connect
  [app opts]
  (connect-tcp-client app opts))
