(ns picard.net.core
  (:use
   picard.core
   picard.net.message
   picard.utils.core)
  (:import
   [org.jboss.netty.buffer
    ChannelBuffer
    ChannelBuffers]
   [org.jboss.netty.channel
    Channels
    ChannelEvent
    ChannelFuture
    ChannelFutureListener
    ChannelHandlerContext
    ChannelState
    ChannelStateEvent
    ChannelUpstreamHandler
    ExceptionEvent
    MessageEvent]
   [org.jboss.netty.channel.group
    ChannelGroup
    ChannelGroupFuture
    ChannelGroupFutureListener
    DefaultChannelGroup]
   [java.net
    InetSocketAddress]
   [java.nio.channels
    ClosedChannelException]
   [java.util
    LinkedList]
   [java.util.concurrent
    Executors]))

(defn mk-thread-pool
  []
  (Executors/newCachedThreadPool))

(defn mk-channel-group
  []
  (DefaultChannelGroup.))

(defn mk-channel-pipeline
  []
  (Channels/pipeline))

(defn ^InetSocketAddress mk-socket-addr
  [[host port]]
  (let [port (or port 80)]
    (if host
      (InetSocketAddress. host port)
      (InetSocketAddress. port))))

;; ==== Futures

(extend-type ChannelFuture
  DeferredValue
  (received? [f] (.isDone f))
  (received  [f] (.isSuccess f))
  (receive [f success error]
    (doto f
      (.addListener
       (reify ChannelFutureListener
         (operationComplete [_ _]
           (success (.isSuccess f))))))))

(extend-type ChannelGroupFuture
  DeferredValue
  (received? [f] (.isDone f))
  (received  [f] (.isCompleteSuccess f))
  (receive [f success error]
    (doto f
      (.addListener
       (reify ChannelGroupFutureListener
         (operationComplete [_ _]
           (success (.isCompleteSuccess f))))))))


;; ==== Helper functions for tracking events
(defn channel-open-event?
  [^ChannelStateEvent evt]
  (and (instance? ChannelStateEvent evt)
       (= ChannelState/OPEN (.getState evt))
       (.getValue evt)))

(defn channel-connected-event?
  [^ChannelStateEvent evt]
  (and (instance? ChannelStateEvent evt)
       (= ChannelState/CONNECTED (.getState evt))
       (.getValue evt)))

(defn channel-disconnected-event?
  [^ChannelStateEvent evt]
  (and (instance? ChannelStateEvent evt)
       (= ChannelState/CONNECTED (.getState evt))
       (not (.getValue evt))))

(defn message-event?
  [evt]
  (instance? MessageEvent evt))

(defn interest-changed-event?
  [^ChannelStateEvent evt]
  (and (instance? ChannelStateEvent evt)
       (= ChannelState/INTEREST_OPS (.getState evt))))

(defn exception-event?
  [evt]
  (instance? ExceptionEvent evt))

;; ==== Handlers

(defrecord State
    [upstream
     downstream
     aborting?
     writable?
     event-lock
     state-queue
     message-queue])

(defrecord NettyState
    [ch
     last-write])

(defn- mk-initial-state
  [dn]
  (State.
   nil             ;; upstream
   dn              ;; downstream
   false           ;; aborting?
   true            ;; writable?
   (atom true)     ;; event-lock
   (LinkedList.)   ;; state-queue
   (LinkedList.))) ;; message-queue

(defn- mk-initial-netty-state
  []
  (NettyState. nil nil))

(declare handle-err)

(defn- addr->ip
  [addr]
  [(.. addr getAddress getHostAddress) (.getPort addr)])

(defn- channel-info
  [ch]
  {:local-addr  (addr->ip (.getLocalAddress ch))
   :remote-addr (addr->ip (.getRemoteAddress ch))})

(defn- close-channel
  [current-state]
  (let [ch (.ch current-state)]
    (doasync (.last-write current-state)
      (fn [_]
        (when (.isOpen ch)
          (.close ch))))))

(defn- try-acquire
  [current-state evt val]
  (let [event-lock    (.event-lock current-state)
        state-queue   (.state-queue current-state)
        message-queue (.message-queue current-state)]
    (locking event-lock
      ;; Clear the queues if the upstream is an abort
      (when (= :abort evt)
        (.clear state-queue)
        (.clear message-queue))
      ;; Add the event to the appropriate queue
      (if (#{:pause :resume :abort} evt)
        (.add state-queue [evt val])
        (.add message-queue [evt val]))
      ;; Finally, attempt to get the lock
      (let [acquired? @event-lock]
        (when acquired? (reset! event-lock false))
        acquired?))))

(defn- poll-queue*
  [current-state]
  (or (.. current-state state-queue poll)
      (.. current-state message-queue poll)
      ;; (and (or (.. current-state ch isReadable)
      ;;          (not (.. current-state ch isOpen)))
      ;;      (.. current-state message-queue poll))
      ))

(defn- poll-queue
  [current-state]
  (let [event-lock (.event-lock current-state)]
    (locking event-lock
      (let [next (poll-queue* current-state)]
        (when-not next
          (reset! event-lock true))
        next))))

(defn- abandon-lock
  [current-state]
  (let [event-lock (.event-lock current-state)]
    (locking event-lock
      (reset! event-lock true))))

(defn- handle-interest-ops
  [state evt upstream]
  (let [current-state @state
        was-writable? (.writable? current-state)
        now-writable? (= :resume evt)]

    (when (not= was-writable? now-writable?)
      (swap! state #(assoc % :writable? now-writable?))
      (upstream evt nil))))

;; Handles sending messages upstream in a sane and thread-safe way.
;;
;; When sending upstream, a lock must first be aquired. When aquired,
;; the message can be sent upstream, otherwise, the messages are queued
;; up.
(defn- send-upstream
  [state evt val current-state]
  (let [upstream  (.upstream current-state)
        acquired? (try-acquire current-state evt val)]
    (when acquired?
      (try
        (loop []
          (when-let [[evt val] (poll-queue current-state)]
            (cond
             (#{:pause :resume} evt)
             (handle-interest-ops state evt upstream)

             (= :schedule evt)
             (val)

             :else
             (upstream evt val))
            (recur)))
        (catch Exception err
          (handle-err state err @state true)
          (abandon-lock current-state))))))

(defn- handle-err
  ([state err current-state]
     (handle-err state err current-state false))
  ([state err current-state locked?]
     (when (not (.aborting? current-state))
       (let [upstream (.upstream current-state)]
         (swap-then!
          state
          #(assoc % :aborting? true)
          (fn [^State current-state]
            (try
              ((.downstream current-state) :close nil)
              (catch Exception _))
            (when upstream
              (try
                (if locked?
                  (upstream :abort err)
                  (send-upstream state :abort err current-state))
                (catch Exception _)))))))))

(defn- mk-downstream
  [next-dn state]
  (fn [evt val]
    (let [current-state @state]
      (when-not (.upstream current-state)
        (throw (Exception. "Not callbable yet")))

      (cond
       (= :schedule evt)
       (send-upstream state :schedule val current-state)

       (= :abort evt)
       (handle-err state val current-state)

       :else
       (next-dn evt val)))))

(defn handler
  [app]
  (fn [dn]
    (let [state    (atom (mk-initial-state dn))
          upstream (app (mk-downstream dn state))]
      ;; Save off the upstream
      (swap! state #(assoc % :upstream upstream))
      ;; And now the upstream
      (fn [evt val]
        (try
          (let [current-state @state]
            (if (= :abort evt)
              (when-not (.aborting? current-state)
                (swap-then!
                 state
                 #(assoc % :aborting? true)
                 #(send-upstream state evt val %)))
              (send-upstream state evt val current-state)))
          (catch Exception err
            (handle-err state err @state)))))))

(defn- encode
  [val]
  (if (buffer? val)
    (to-channel-buffer val)
    val))

(defn- mk-netty-downstream-fn
  [state]
  (fn [evt val]
    (let [current-state @state]
      (cond
       (= :message evt)
       (let [ch (.ch current-state)]
         (when-not (.isOpen ch)
           (throw (ClosedChannelException.)))

         (let [last-write (.write ch (encode val))]
           (swap! state #(assoc % :last-write last-write))))

       (= :close evt)
       (close-channel current-state)

       (= :pause evt)
       (.setReadable (.ch current-state) false)

       (= :resume evt)
       (.setReadable (.ch current-state) true)

       :else
       (throw (Exception. (str "Unexpected event: " evt)))))))

(defn mk-upstream-handler
  [^ChannelGroup channel-group app _]
  (let [state    (atom (mk-initial-netty-state))
        app      (handler app)
        upstream (app (mk-netty-downstream-fn state))]

    ;; The actual Netty upstream handler.
    (reify ChannelUpstreamHandler
      (^void handleUpstream [_ ^ChannelHandlerContext ctx ^ChannelEvent evt]
        (let [current-state ^State @state]
          (cond
           ;; Handle message events
           (message-event? evt)
           (let [[evt val] (decode (.getMessage ^MessageEvent evt))]
             (upstream evt val))

           (interest-changed-event? evt)
           (if (.isWritable (.getChannel evt))
             (upstream :resume nil)
             (upstream :pause nil))

           ;; The bind function is invoked on channel open, ideally
           ;; this would always get invoked. However, there is a
           ;; possibility that opening the socket channel
           ;; fails. That case isn't handled right now.
           (channel-open-event? evt)
           (let [ch (.getChannel evt)]
             ;; First, track the channel in the channel group
             (.add channel-group ch)
             ;; Now, initialize the state with the channel
             (swap! state #(assoc % :ch ch)))

           (channel-connected-event? evt)
           (let [ch-info (-> current-state .ch channel-info)]
             (upstream :open ch-info))

           (channel-disconnected-event? evt)
           (upstream :close nil)

           (exception-event? evt)
           (upstream :abort (.getCause evt))))))))
