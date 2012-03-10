(ns ^{:author "Carl Lerche"
      :doc "Provides non-blocking DNS resolution."}
  momentum.net.dns
  (:use
   momentum.core)
  (:import
   [java.net
    InetAddress]
   [java.util.concurrent
    Executors]))

(def resolv-pool (Executors/newCachedThreadPool))

(defn- resolv-runner
  [val hostname]
  (reify Runnable
    (run [_]
      (put val (InetAddress/getByName hostname)))))

(defn lookup
  "Returns an async-val that will be realized with the InetAddress of
  the given hostname."
  [hostname]
  (let [val (async-val)]
    (.submit resolv-pool (resolv-runner val hostname))
    val))