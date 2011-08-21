(ns picard.net.server
  (:use
   picard.net.core)
  (:import
   [org.jboss.netty.channel
    ChannelPipelineFactory]
   [org.jboss.netty.channel.group
    ChannelGroup]
   [org.jboss.netty.channel.socket.nio
    NioServerSocketChannelFactory]))

(def default-opts
  {"reuseAddress"               true
   "child.reuseAddres"          true,
   "child.connectTimeoutMillis" 100})

(defn- merge-netty-opts
  [opts]
  (merge
   default-opts
   (reduce
    (fn [opts [k v]]
      (cond
       (= :keep-alive k)
       (assoc opts "child.keepAlive" v)
       (= :tcp-no-delay k)
       (assoc opts "tcpNoDelay" v "child.tcpNoDelay" v)
       (= :send-buffer-size k)
       (assoc opts "child.sendBufferSize" v)
       (= :receive-buffer-size k)
       (assoc opts "child.receiveBufferSize" v)
       (= :reuse-address k)
       (assoc opts "reuseAddress" v "child.reuseAddress" v)
       (= :connect-timeout k)
       (assoc opts "child.connectTimeoutMillis" v)
       :else
       opts))
    {}
    opts)
   (opts :netty)))

(defn- ^ChannelPipelineFactory mk-pipeline-factory
  [channel-group opts]
  (let [pipeline-fn     (or (opts :pipeline-fn) (fn [p _] p))
        channel-tracker (mk-channel-tracker channel-group)]
      (reify ChannelPipelineFactory
        (getPipeline [_]
          (doto (mk-pipeline)
            (pipeline-fn opts)
            (.addFirst "channel-tracker" channel-tracker))))))

(defn- ^ServerBootstrap mk-server-bootstrap
  [thread-pool]
  (ServerBootstrap.
   (NioServerSocketChannelFactory.
    thread-pool thread-pool)))

(defn start
  [app {host :host port :port :as opts}]
  (let [thread-pool   (mk-thread-pool)
        bootstrap     (mk-server-bootstrap thread-pool)
        channel-group (mk-channel-group)
        socket-addr   (mk-socket-addr [host port])])

  ;; Set the options
  (doseq [[k v] (merge-netty-opts opts)]
    (.setOption bootstrap k v))

  ;; Set the factory
  (.setPipelineFactory
   bootstrap
   (mk-pipeline-factory channel-group opts))

  {::bootstrap      bootstrap
   ::server-channel (.bind bootstrap socket-addr)
   ::channel-group  channel-group})

(defn stop
  [server])
