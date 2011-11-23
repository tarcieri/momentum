(ns momentum.net.client
  (:use
   momentum.core
   momentum.core.timer
   momentum.net.core)
  (:require
   [momentum.net.pool :as pool])
  (:import
   [org.jboss.netty.channel.socket.nio
    NioClientSocketChannelFactory]
   [momentum.net
    ClientBootstrap]))

(defn- ^ClientBootstrap mk-bootstrap
  [thread-pool]
  (ClientBootstrap.
   (NioClientSocketChannelFactory. thread-pool thread-pool)))

(defn- mk-client-pipeline
  [channel-group app {pipeline-fn :pipeline-fn :as opts}]
  (let [pipeline-fn (or pipeline-fn (fn [p _] p))
        handler     (mk-upstream-handler channel-group app opts)]
    (doto (mk-channel-pipeline)
      (pipeline-fn opts)
      (.addLast "handler" handler))))

(def default-opts
  {"reuseAddress"         true
   "connectTimeoutMillis" 3000})

(defn- merge-netty-opts
  [opts]
  (merge
   default-opts
   (reduce
    (fn [opts [k v]]
      (cond
       (= :keep-alive k)
       (assoc opts "keepAlive" v)
       (= :tcp-no-delay k)
       (assoc opts "tcpNoDelay" v)
       (= :send-buffer-size k)
       (assoc opts "sendBufferSize" v)
       (= :receive-buffer-size v)
       (assoc opts "receiveBufferSize" v)
       (= :reuse-address k)
       (assoc opts "reuseAddress" v)
       (= :connect-timeout k)
       (assoc opts "connectTimeoutMillis" v)
       :else
       opts))
    {}
    opts)
   (opts :netty)))

(defprotocol Client
  (do-connect  [_ app addr opts])
  (do-release  [_]))

(defrecord BasicClient  [channel-group bootstrap]
  Client
  (do-connect [client app addr opts]
    (let [ch-group (.channel-group client)
          pipeline (mk-client-pipeline ch-group app opts)]
      (.connect (.bootstrap client) addr pipeline)))

  (do-release [client]
    (doasync
      (ch-group-future-as-async-val (.. client channel-group close))
      (fn [_]
        (.releaseExternalResources (.bootstrap client)))))

  clojure.lang.IFn
  (invoke [this app {host :host port :port :as opts}]
    (do-connect this app (mk-socket-addr [host port]) opts)
    true))

(defrecord PooledClient [basic-client pool]
  Client
  (do-connect [client app addr opts]
    (pool/connect
     (.pool client) app addr
     #(do-connect (.basic-client client) % addr opts)))

  (do-release [client]
    (do-release (.basic-client client)))

  clojure.lang.IFn
  (invoke [_]
    (throw (IllegalArgumentException. "Must specify a bind fn and opts")))
  (invoke [_ app?]
    (throw
     (IllegalArgumentException.
      (if (map? app?)
        "Must specify a bind fn then opts"
        "Must specify an options map containing at least :host and :port"))))
  (invoke [this app {host :host port :port :as opts}]
    (do-connect this app (mk-socket-addr [host port]) opts)
    true))

(defn- basic-client
  [opts]
  (let [thread-pool   (mk-thread-pool)
        bootstrap     (mk-bootstrap thread-pool)
        channel-group (mk-channel-group)]

    ;; Set the options
    (doseq [[k v] (merge-netty-opts opts)]
      (.setOption bootstrap k v))

    (BasicClient. channel-group bootstrap)))

(defn- pooled-client
  [basic-client opts]
  (PooledClient. basic-client (pool/mk-pool opts)))

(defn client
  ([] (client {}))
  ([opts]
     (let [client (basic-client opts)]
       (if-let [opts (opts :pool)]
         (pooled-client client opts)
         client))))

(def default-client (client))

(defn release
  [client]
  (do-release client))

(defn connect
  ([app opts]
     (default-client app opts))
  ([client app opts]
     (client app opts)))
