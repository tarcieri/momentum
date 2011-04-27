(ns picard.netty
  (:require
   [picard.formats :as f])
  (:import
   [org.jboss.netty.bootstrap
    ServerBootstrap]
   [org.jboss.netty.channel
    Channel
    ChannelEvent
    ChannelFuture
    ChannelFutureListener
    ChannelPipeline
    ChannelPipelineFactory
    ChannelState
    ChannelStateEvent
    ChannelUpstreamHandler
    Channels
    ExceptionEvent
    MessageEvent]
   [org.jboss.netty.channel.group
    ChannelGroup
    ChannelGroupFuture
    ChannelGroupFutureListener
    DefaultChannelGroup]
   [org.jboss.netty.channel.socket.nio
    NioClientSocketChannelFactory
    NioServerSocketChannelFactory]
   [java.net
    InetSocketAddress]
   [java.util.concurrent
    Executors]))

(defprotocol NETTY-FUTURE
  (on-complete [future callback]))

(extend-type ChannelGroupFuture
  NETTY-FUTURE
  (on-complete [future callback]
    (.addListener future
                  (reify ChannelGroupFutureListener
                    (operationComplete [_ future]
                      (callback future))))
    future))

(extend-type ChannelFuture
  NETTY-FUTURE
  (on-complete [future callback]
    (.addListener future
                  (reify ChannelFutureListener
                    (operationComplete [_ future]
                      (callback future))))
    future))

(def close-channel-future-listener ChannelFutureListener/CLOSE)

(defmacro create-pipeline
  [& stages]
  (let [pipeline-var (gensym "pipeline")]
    `(let [~pipeline-var (Channels/pipeline)]
      ~@(map
         (fn [[id# stage#]] (list '.addLast pipeline-var (name id#) stage#))
         (partition 2 stages))
      ~pipeline-var)))

(defn mk-thread-pool
  []
  (Executors/newCachedThreadPool))

(defn- mk-server-bootstrap
  []
  (ServerBootstrap.
   (NioServerSocketChannelFactory.
    (mk-thread-pool) (mk-thread-pool))))

(defn- client-channel-factory
  [thread-pool]
  (NioClientSocketChannelFactory. thread-pool thread-pool))

(defn message-event
  "Returns contents of message event, or nil if it's a
   different type of message."
  [evt]
  (when (instance? MessageEvent evt)
    (.getMessage ^MessageEvent evt)))

(defn- channel-event
  [evt]
  (when (instance? ChannelEvent evt)
    (.getChannel ^ChannelEvent evt)))

(defn- channel-state-event
  [evt]
  (when (instance? ChannelStateEvent evt)
    (.getChannel ^ChannelStateEvent evt)))

(defn upstream-stage
  "Creates a pipeline state for upstream events."
  [handler]
  (reify ChannelUpstreamHandler
    (handleUpstream [_ ctx evt]
      ;; Temporary ghetto exception tracking
      (if (instance? ExceptionEvent evt)
        (let [cause (.getCause ^ExceptionEvent evt)]
          (println "GOT AN EXCEPTION")
          (println (.getMessage cause))
          (.printStackTrace cause)))
      (if-let [upstream-evt (handler evt)]
        (.sendUpstream ctx upstream-evt)
        (.sendUpstream ctx evt)))))

(defn message-stage
  "Creates a final upstream stage that only captures MessageEvents."
  [handler]
  (upstream-stage
   (fn [evt]
     (when-let [msg (message-event evt)]
       (handler (.getChannel ^MessageEvent evt) msg)))))

(defn- mk-pipeline-factory
  [^ChannelGroup channel-group pipeline-fn & args]
  (reify ChannelPipelineFactory
    (getPipeline [_]
      (let [pipeline ^ChannelPipeline (apply pipeline-fn args)]
        (when false
          (.addFirst pipeline
                    "channel-listener"
                    (upstream-stage
                     (fn [evt]
                       (when-let [ch ^Channel (channel-state-event evt)]
                         (when (= ChannelState/OPEN (.getState evt))
                           (.add channel-group ch)))
                       nil))))
        pipeline))))

(defn start-server
  "Starts a server. Returns a function that stops the server"
  [pipeline-fn {port :port :as options}]
  (let [server (mk-server-bootstrap)
        channel-group (DefaultChannelGroup.)]
    ;; Create the pipeline factory based on the passed
    ;; function
    (.setPipelineFactory server
                         (mk-pipeline-factory channel-group pipeline-fn))
    (.add channel-group (.bind server (InetSocketAddress. port)))
    (fn []
      (on-complete (.close channel-group)
                   (fn [_] (.releaseExternalResources server))))))

(defn connect-client
  ([pipeline addr on-connected]
     (connect-client pipeline addr on-connected (mk-thread-pool)))
  ([pipeline addr on-connected thread-pool]
     (let [channel-factory (client-channel-factory thread-pool)
           channel (.newChannel channel-factory pipeline)]
       (on-complete
        (.connect channel (InetSocketAddress. addr 80))
        (fn [_] (on-connected channel)))
       channel)))
