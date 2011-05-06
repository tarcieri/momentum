(ns picard.netty
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
    MessageEvent
    WriteCompletionEvent]
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

(extend-type nil
  NETTY-FUTURE
  (on-complete [_ callback]
    (callback nil)))

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

(defn channel-state-event
  [evt]
  (when (instance? ChannelStateEvent evt)
    [(.getState ^ChannelStateEvent evt) (.getValue ^ChannelStateEvent evt)]))

(defn channel-connect-event?
  "Whether or not the passed channel event is a channel
   connect event"
  [evt]
  (let [[state val] (channel-state-event evt)]
    (and (= ChannelState/CONNECTED state)
         (not (nil? val)))))

(defn exception-event
  [evt]
  (when (instance? ExceptionEvent evt)
    (.getCause ^ExceptionEvent evt)))

(defn write-completion-event
  [evt]
  (instance? WriteCompletionEvent evt))

(defn upstream-stage
  "Creates a pipeline state for upstream events."
  [handler]
  (reify ChannelUpstreamHandler
    (handleUpstream [_ ctx evt]
      (handler (.getChannel evt) evt)
      (.sendUpstream ctx evt))))

;; (defn message-stage
;;   "Creates a final upstream stage that only captures MessageEvents."
;;   [handler]
;;   (upstream-stage
;;    (fn [evt]
;;      (when-let [msg (message-event evt)]
;;        (handler (.getChannel ^MessageEvent evt) msg)))))

(defn- mk-pipeline-factory
  [^ChannelGroup channel-group pipeline-fn]
  (reify ChannelPipelineFactory
    (getPipeline [_]
      (let [pipeline ^ChannelPipeline (pipeline-fn)]
        (.addFirst
         pipeline "channel-listener"
         (upstream-stage
          (fn [ch evt]
            (when-let [state (channel-state-event evt)]
              (when (= ChannelState/OPEN state)
                (.add channel-group ch))))))
        pipeline))))

(defn start-server
  "Starts a server. Returns a function that stops the server"
  [pipeline-fn {port :port :as options}]
  (let [server        (mk-server-bootstrap)
        channel-group (DefaultChannelGroup.)]
    ;; Create the pipeline factory based on the passed
    ;; function
    (.setPipelineFactory
     server
     (mk-pipeline-factory
      channel-group
      ;; If the user passed in a pipeline function, wrap the
      ;; base pipeline function with the user's function. Supplied
      ;; pipeline functions must take in a pipeline and return
      ;; the new pipeline.
      (if-let [user-pipeline-fn (options :pipeline-fn)]
        #(user-pipeline-fn (pipeline-fn))
        pipeline-fn)))

    (.add channel-group (.bind server (InetSocketAddress. port)))

    ;; Return a server shutdown function
    (fn [] (on-complete (.close channel-group)
                       (fn [_] (.releaseExternalResources server))))))

(defn connect-client
  ([pipeline host port on-connected]
     (connect-client pipeline host port on-connected (mk-thread-pool)))
  ([pipeline host port on-connected thread-pool]
     (let [channel-factory (client-channel-factory thread-pool)
           channel (.newChannel channel-factory pipeline)]
       (on-complete
        (.connect channel (InetSocketAddress. host port))
        (fn [_] (on-connected channel)))
       channel)))
