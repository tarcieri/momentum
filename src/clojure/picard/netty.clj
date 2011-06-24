(ns picard.netty
  (:use
   [picard.utils])
  (:import
   [org.jboss.netty.bootstrap
    ClientBootstrap
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
    ChildChannelStateEvent
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
   [org.jboss.netty.handler.timeout
    IdleStateEvent]
   [org.jboss.netty.util
    HashedWheelTimer
    Timeout
    TimerTask]
   [java.net
    InetSocketAddress]
   [java.util
    LinkedList]
   [java.util.concurrent
    Executors
    TimeUnit]))

(defprotocol INettyFuture
  (on-complete [future callback]))

(extend-type ChannelGroupFuture
  INettyFuture
  (on-complete [future callback]
    (.addListener future
                  (reify ChannelGroupFutureListener
                    (operationComplete [_ future]
                      (callback future))))
    future))

(extend-type ChannelFuture
  INettyFuture
  (on-complete [future callback]
    (.addListener future
                  (reify ChannelFutureListener
                    (operationComplete [_ future]
                      (callback future))))
    future))

(extend-type nil
  INettyFuture
  (on-complete [future callback]
    (callback future)))

(def close-channel-future-listener ChannelFutureListener/CLOSE)

(defn mk-timer
  ([] (mk-timer 100))
  ([ms] (HashedWheelTimer. ms TimeUnit/MILLISECONDS)))

(def global-timer (mk-timer 1000))

(defn on-timeout
  [^HashedWheelTimer timer ^long ms f]
  (.newTimeout
   timer (reify TimerTask (run [_ _] (f)))
   ms TimeUnit/MILLISECONDS))

(defn cancel-timeout
  [^Timeout timeout]
  (.cancel timeout))

(defn create-pipeline
  [& stages]
  (let [pipeline (Channels/pipeline)]
    (doseq [[stage-name stage] (partition 2 stages)]
      (.addLast pipeline (name stage-name) stage))
    pipeline))

(defn mk-socket-addr
  [[host port]]
  (if host
    (InetSocketAddress. host (or port 80))
    (InetSocketAddress. (or port 80))))

(defn mk-thread-pool
  []
  (Executors/newCachedThreadPool))

(defn- mk-server-bootstrap
  [thread-pool]
  (ServerBootstrap.
   (NioServerSocketChannelFactory.
    thread-pool thread-pool)))

(defn- mk-client-bootstrap
  [thread-pool]
  (ClientBootstrap.
   (NioClientSocketChannelFactory. thread-pool thread-pool)))

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

(defn channel-disconnected-event?
  [evt]
  (and (instance? ChannelStateEvent evt)
       (= ChannelState/CONNECTED (.getState ^ChannelStateEvent evt))
       (nil? (.getValue ^ChannelStateEvent evt))))

(defn channel-interest-changed-event?
  [evt]
  (and (instance? ChannelStateEvent evt)
       (let [state (.getState ^ChannelStateEvent evt)]
         (= ChannelState/INTEREST_OPS state))))

(defn exception-event
  [evt]
  (when (instance? ExceptionEvent evt)
    (.getCause ^ExceptionEvent evt)))

(defn write-completion-event
  [evt]
  (instance? WriteCompletionEvent evt))

(defn unknown-channel-event?
  "Not a known netty channel event"
  [evt]
  (not (or (instance? WriteCompletionEvent evt)
           (instance? ChannelStateEvent evt)
           (instance? ChildChannelStateEvent evt)
           (instance? ExceptionEvent evt)
           (instance? IdleStateEvent evt)
           (instance? MessageEvent evt))))

(defn upstream-stage
  "Creates a pipeline state for upstream events."
  [handler]
  (reify ChannelUpstreamHandler
    (handleUpstream [_ ctx evt]
      (handler (.getChannel evt) evt)
      (.sendUpstream ctx evt))))

(defn- purge-evt-buffer
  [list ctx evt ch]
  (loop []
    (when (.isReadable ch)
      (when-let [evt (.poll list)]
        (.sendUpstream ctx evt)
        (recur)))))

(defn message-pauser
  "A handler that will capture the messages when the channel should not
   be readable. When the channel is not readable, new data is not accepted
   off of the socket. However, there might be other 'miss behaving' handlers
   that will send message events upstream even if the channel is not marked
   as readable. This is most likely the correct thing to do, however, not for
   me."
  []
  (let [list (LinkedList.)]
    (reify ChannelUpstreamHandler
      (handleUpstream [_ ctx evt]
        ;; Events can get fired on multiple threads.
        ;; Good times for all! There is probably a
        ;; better way to handle it than locking this
        ;; handler, but throwing a lock here doesn't
        ;; bother me much.
        (locking list
          (try
            (let [ch ^Channel (.getChannel evt)]
              (cond
               (instance? MessageEvent evt)
               (do
                 (.add list evt)
                 (purge-evt-buffer list ctx evt ch))

               (and (instance? ChannelStateEvent evt)
                    (= ChannelState/INTEREST_OPS (.getState ^ChannelState evt)))
               (do (purge-evt-buffer list ctx evt ch)
                   (.sendUpstream ctx evt))
               :else
               (.sendUpstream ctx evt)))
            (catch Exception err
              (.printStackTrace err))))))))

(defn- mk-pipeline-factory
  [^ChannelGroup channel-group pipeline-fn opts]
  (let [pipeline-fn
        ;; If the user passed in a pipeline function, wrap the
        ;; base pipeline function with the user's function. Supplied
        ;; functions must take in a pipeline and return a new pipeline.
        (if-let [user-pipeline-fn (opts :pipeline-fn)]
          #(user-pipeline-fn (pipeline-fn))
          pipeline-fn)]
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
          pipeline)))))

(def default-server-opts
  {"reuseAddress"               true
   "child.reuseAddres"          true,
   "child.connectTimeoutMillis" 100})

(defn- merge-netty-server-opts
  [opts]
  (merge
   default-server-opts
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

(def default-client-opts
  {"reuseAddress"         true
   "connectTimeoutMillis" 3000})

(defn- merge-netty-client-opts
  [opts]
  (merge
   default-client-opts
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

(defn- configure-bootstrap
  [bootstrap opt-merge-fn pipeline-fn options]
  (returning
   [channel-group (DefaultChannelGroup.)]
   ;; First set the options for the bootstrapper based
   ;; on the supplied options and the option merger function
   (doseq [[k v] (opt-merge-fn options)]
     (.setOption bootstrap k v))
   ;; Creates a pipeline factory based on the passed function
   ;; and that will also add all channels to the channel group
   (.setPipelineFactory
    bootstrap
    (mk-pipeline-factory channel-group pipeline-fn options))))

(defn shutdown
  [{bootstrap ::bootstrap srv-ch ::server-channel ch-group ::channel-group}]
  (.add ch-group srv-ch)
  (let [close-future (.close ch-group)]
    (.awaitUninterruptibly close-future)
    (.releaseExternalResources bootstrap)))

(defn start-server
  "Starts a server. Returns a function that stops the server"
  [pipeline-fn {host :host port :port :as options}]
  (let [bootstrap (mk-server-bootstrap (mk-thread-pool))
        ch-group  (configure-bootstrap
                   bootstrap merge-netty-server-opts pipeline-fn options)]
    {::bootstrap      bootstrap
     ::server-channel (.bind bootstrap (mk-socket-addr [host port]))
     ::channel-group  ch-group}))

(defn restart-server
  [{srv-ch ::server-channel :as server} pipeline-fn opts]
  (when-not srv-ch
    (throw (IllegalArgumentException.
            "Server state is missing the channel")))
  (let [ch-group (DefaultChannelGroup.)]
    (.. srv-ch
        getConfig
        (setPipelineFactory
         (mk-pipeline-factory ch-group pipeline-fn opts)))

    ;; Return the new server state
    {::bootstrap     (server ::bootstrap)
     ::server-channel srv-ch
     ::channel-group  ch-group}))

(defn mk-client-factory
  [pipeline-fn options]
  (let [bootstrap (mk-client-bootstrap (mk-thread-pool))
        ch-group  (configure-bootstrap
                   bootstrap merge-netty-client-opts pipeline-fn options)]
    [bootstrap ch-group]))

(defn connect-client
  ([factory addr callback]
     (connect-client factory addr nil callback))
  ([[bootstrap] addr local-addr callback]
     (on-complete
      (if local-addr
        (.connect bootstrap (mk-socket-addr addr) (mk-socket-addr local-addr))
        (.connect bootstrap (mk-socket-addr addr)))
      (fn [future]
        (if (.isSuccess future)
          (callback (.getChannel future))
          (callback (.getCause future)))))))
