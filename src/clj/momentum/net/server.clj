(ns momentum.net.server
  (:use
   momentum.core
   momentum.net.core)
  (:import
   [org.jboss.netty.bootstrap
    Bootstrap
    ServerBootstrap]
   [org.jboss.netty.channel
    ChannelPipelineFactory]
   [org.jboss.netty.channel.group
    ChannelGroup]
   [org.jboss.netty.channel.socket.nio
    NioServerSocketChannelFactory]))

(defn- ^ChannelPipelineFactory mk-pipeline-factory
  [channel-group app {pipeline-fn :pipeline-fn :as opts}]
  (let [pipeline-fn (or pipeline-fn (fn [p _] p))]
      (reify ChannelPipelineFactory
        (getPipeline [_]
          (let [handler (mk-upstream-handler channel-group app opts)]
            (doto (mk-channel-pipeline)
              (pipeline-fn opts)
              (.addLast "handler" handler)))))))

(defn- ^ServerBootstrap mk-bootstrap
  [thread-pool]
  (ServerBootstrap.
   (NioServerSocketChannelFactory.
    thread-pool thread-pool)))

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

(defn start
  ([app] (start app {}))
  ([app {host :host port :port :as opts}]
     (let [thread-pool   (mk-thread-pool)
           bootstrap     (mk-bootstrap thread-pool)
           channel-group (mk-channel-group)
           socket-addr   (mk-socket-addr [host (or port 4040)])]

       ;; Set the options
       (doseq [[k v] (merge-netty-opts opts)]
         (.setOption bootstrap k v))

       ;; Set the factory
       (.setPipelineFactory
        bootstrap
        (mk-pipeline-factory channel-group app opts))

       {::bootstrap      bootstrap
        ::server-channel (.bind bootstrap socket-addr)
        ::channel-group  channel-group})))

(defn stop
  [{^Bootstrap bootstrap        ::bootstrap
    server-channel              ::server-channel
    ^ChannelGroup channel-group ::channel-group}]
  ;; Add the server channel to the channel group, this
  ;; way we can shutdown everything at once
  (.add channel-group server-channel)
  (let [close-future (.close channel-group)]
    (.awaitUninterruptibly close-future)
    (.releaseExternalResources bootstrap)))
