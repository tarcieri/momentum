(ns picard.net.server
  (:use
   picard.net.core)
  (:import
   [org.jboss.netty.bootstrap
    Bootstrap
    ServerBootstrap]
   [org.jboss.netty.channel
    ChannelEvent
    ChannelHandlerContext
    ChannelPipelineFactory
    ChannelUpstreamHandler
    ExceptionEvent
    MessageEvent]
   [org.jboss.netty.channel.group
    ChannelGroup]
   [org.jboss.netty.channel.socket.nio
    NioServerSocketChannelFactory]))

(defrecord State
    [ch
     upstream
     aborting?])

(defn- initial-state
  [ch upstream]
  (State. ch upstream false))

(defn- handle-err
  [state err])

(defn- mk-downstream-fn
  [state]
  (fn [evt val]))

(defn- mk-upstream-handler
  [app opts]
  (let [state (atom nil)]
    (reify ChannelUpstreamHandler
      (^void handleUpstream [_ ^ChannelHandlerContext ctx ^ChannelEvent evt]
        (let [current-state ^State @state]
          (cond
           ;; Handle message events
           (message-event? evt)
           (let [msg      (.getMessage ^MessageEvent evt)
                 upstream (.upstream current-state)]
             (try
               (upstream :message msg)
               (catch Exception err
                 (handle-err state err))))

           ;; The connection has been established. Bind the application function
           ;; with a downstream and set the initial state. The
           ;; downstream should not be invoked during the binding
           ;; phase, so an open event will be fired.
           (channel-open-event? evt)
           (let [ch       (.getChannel evt)
                 upstream (app (mk-downstream-fn state))]
             (reset! state (initial-state ch upstream))
             (try
               (upstream :open nil)
               (catch Exception err
                 (handle-err state err))))

           (channel-close-event? evt)
           (let [upstream (.upstream current-state)]
             (try
               (upstream :close nil)
               (catch Exception err
                 (handle-err state err))))

           (exception-event? evt)
           (handle-err state (.getCause evt))))))))

(defn- ^ChannelPipelineFactory mk-pipeline-factory
  [app channel-group opts]
  (let [pipeline-fn     (or (opts :pipeline-fn) (fn [p _] p))
        channel-tracker (mk-channel-tracker channel-group)]
      (reify ChannelPipelineFactory
        (getPipeline [_]
          (doto (mk-pipeline)
            (pipeline-fn opts)
            (.addFirst "channel-tracker" channel-tracker)
            (.addLast "handler" (mk-upstream-handler app opts)))))))

(defn- ^ServerBootstrap mk-server-bootstrap
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
           bootstrap     (mk-server-bootstrap thread-pool)
           channel-group (mk-channel-group)
           socket-addr   (mk-socket-addr [host (or port 4040)])]

       ;; Set the options
       (doseq [[k v] (merge-netty-opts opts)]
         (.setOption bootstrap k v))

       ;; Set the factory
       (.setPipelineFactory
        bootstrap
        (mk-pipeline-factory app channel-group opts))

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
