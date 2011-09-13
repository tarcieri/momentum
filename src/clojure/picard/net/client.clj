(ns picard.net.client
  (:use
   picard.core.deferred
   picard.net.core
   picard.utils)
  (:import
   [org.jboss.netty.channel.socket.nio
    NioClientSocketChannelFactory]
   [picard.net
    ClientBootstrap]))

(defn- ^ClientBootstrap mk-bootstrap
  [thread-pool]
  (ClientBootstrap.
   (NioClientSocketChannelFactory. thread-pool thread-pool)))

(defn- mk-client-pipeline
  [channel-group app {pipeline-fn :pipeline-fn :as opts}]
  (let [pipeline-fn (or pipeline-fn (fn [p _] p))
        handler     (mk-upstream-handler channel-group app opts)]
    (doto (mk-pipeline)
      (pipeline-fn opts)
      (.addLast "handler" handler))))

(def default-opts
  {"reuseAddress"               true
   "child.reuseAddres"          true,
   "child.connectTimeoutMillis" 100})

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
  (do-connect [_ app opts])
  (do-release [_]))

(defrecord BasicClient  [channel-group bootstrap]
  Client
  (do-connect [client app {host :host port :port :as opts}]
    (let [bootstrap     (.bootstrap client)
          channel-group (.channel-group client)
          addr          (mk-socket-addr [host port])
          pipeline      (mk-client-pipeline channel-group app opts)]
      (.connect bootstrap addr pipeline)))
  (do-release [client]
    (receive
     (.. client channel-group close)
     (fn [_ _ _]
       (.releaseExternalResources (.bootstrap client))))))

(defrecord PooledClient [basic-client pool]
  Client
  (do-connect [client app opts]
    (do-connect (.basic-client client) app opts))
  (do-release [client]
    (do-release (.basic-client client))))

(defn client
  ([] (client {}))
  ([opts]
     (let [thread-pool   (mk-thread-pool)
           bootstrap     (mk-bootstrap thread-pool)
           channel-group (mk-channel-group)]

       ;; Set the options
       (doseq [[k v] (merge-netty-opts opts)]
         (.setOption bootstrap k v))

       (BasicClient. channel-group bootstrap))))

(def default-client (client))

(defn release
  [client]
  (.do-release client))

(defn connect
  ([app opts] (connect default-client app opts))
  ([client app opts]
     (do-connect client app opts)))
