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

(defn client
  ([] (client {}))
  ([opts]
     (let [thread-pool   (mk-thread-pool)
           bootstrap     (mk-bootstrap thread-pool)
           channel-group (mk-channel-group)]

       ;; Set the options
       (doseq [[k v] (merge-netty-opts opts)]
         (.setOption bootstrap k v))

       ;; Configure the bootstrap
       {::channel-group channel-group
        ::bootstrap     bootstrap})))

(def default-client (client))

(defn release
  [{channel-group ::channel-group bootstrap ::bootstrap}]
  (receive
   (.close channel-group)
   (fn [_ _ _]
     (.releaseExternalResources bootstrap))))

(defn connect
  ([app opts] (connect default-client app opts))
  ([client app {host :host port :port :as opts}]
     (let [bootstrap     (client ::bootstrap)
           channel-group (client ::channel-group)
           addr         (mk-socket-addr [host port])
           pipeline     (mk-client-pipeline channel-group app opts)]
       (.connect bootstrap addr pipeline))))
