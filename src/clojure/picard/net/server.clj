(ns picard.net.server
  (:use
   picard.core.deferred
   picard.utils
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
    NioServerSocketChannelFactory]
   [java.util
    LinkedList]))

(declare handle-err)

(defrecord State
    [ch
     upstream
     aborting?
     last-write
     event-lock
     event-queue])

(defn- initial-state
  [ch]
  (State. ch nil false nil (atom true) (LinkedList.)))

(defn- close-channel
  [current-state]
  (let [ch (.ch current-state)]
    (receive
     (.last-write current-state)
     (fn [_ _ _]
       (when (.isOpen ch)
         (.close ch))))))

(defn- try-acquire
  [event-lock event-queue evt val]
  (locking event-lock
    (let [acquired? @event-lock]
      (if acquired?
        (reset! event-lock false)
        (do
          (when (= :abort evt)
            (.clear event-queue))
          (.add event-queue [evt val])))
      acquired?)))

(defn- poll-queue
  [event-lock event-queue]
  (locking event-lock
    (let [next (.poll event-queue)]
      (when-not next
        (reset! event-lock true))
      next)))

(defn- abandon-lock
  [event-lock]
  (locking event-lock
    (reset! event-lock true)))

(defn send-upstream
  [state evt val current-state]
  (let [event-lock  (.event-lock current-state)
        event-queue (.event-queue current-state)
        acquired?   (try-acquire event-lock event-queue evt val)]
    (when acquired?
      (try
        (loop [evt evt val val]
          (let [upstream (.upstream current-state)]
            (upstream evt val))
          (when-let [[evt val] (poll-queue event-lock event-queue)]
            (recur evt val)))
        (catch Exception err
          (handle-err state err @state true)
          (abandon-lock event-lock))))))

(defn- handle-err
  ([state err current-state]
     (handle-err state err current-state false))
  ([state err current-state locked?]
     (let [aborting? (.aborting? current-state)
           upstream  (.upstream current-state)]
       (when-not aborting?
         (swap-then!
          state
          #(assoc % :aborting? true)
          (fn [^State current-state]
            (close-channel current-state)
            (when upstream
              (try
                (if locked?
                  (upstream :abort err)
                  (send-upstream state :abort err current-state))
                (catch Exception _)))))))))

(defn- mk-downstream-fn
  [state]
  (fn [evt val]
    (let [current-state ^State @state]
      (cond
       (= :message evt)
       (if-not (.upstream current-state)
         (throw (Exception.
                 (str "Not callable until request is sent.\n"
                      "  Event: " evt "\n"
                      "  Value: " val)))
         (let [val        (to-channel-buffer val)
               ch         (.ch current-state)
               last-write (.write ch val)]
           (swap! state #(assoc % :last-write last-write))))

       (= :close evt)
       (close-channel current-state)

       (= :pause evt)
       (throw (Exception. "Not implemented"))

       (= :resume evt)
       (throw (Exception. "Not implemented"))

       (= :abort evt)
       (handle-err state val current-state)))))

(defn- mk-upstream-handler
  [app opts]
  (let [state (atom nil)]
    (reify ChannelUpstreamHandler
      (^void handleUpstream [_ ^ChannelHandlerContext ctx ^ChannelEvent evt]
        (let [current-state ^State @state]
          (try
            (cond
             ;; Handle message events
             (message-event? evt)
             (let [msg (.getMessage ^MessageEvent evt)]
               (send-upstream state :message msg current-state))

             ;; The connection has been established. Bind the application function
             ;; with a downstream and set the initial state. The
             ;; downstream should not be invoked during the binding
             ;; phase, so an open event will be fired.
             (channel-open-event? evt)
             (let [ch (.getChannel evt)]
               (reset! state (initial-state ch))
               (let [upstream (app (mk-downstream-fn state))]
                 (swap! state #(assoc % :upstream upstream))
                 (send-upstream state :open nil @state)))

             (channel-close-event? evt)
             (send-upstream state :close nil current-state)

             (exception-event? evt)
             (handle-err state (.getCause evt) current-state))
            (catch Exception err
              (handle-err state err @state))))))))

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
