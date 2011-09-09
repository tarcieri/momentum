(ns picard.net.core
  (:use
   picard.core.deferred
   picard.net.message
   picard.utils)
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

(defn mk-pipeline
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
  (receive [future callback]
    (.addListener
     future
     (reify ChannelFutureListener
       (operationComplete [_ _]
         (callback future (.isSuccess future) true))))))

(extend-type ChannelGroupFuture
  DeferredValue
  (receive [future callback]
    (.addListener
     future
     (reify ChannelGroupFutureListener
       (operationComplete [_ _]
         (callback future (.isCompleteSuccess future) true))))))


;; ==== Helper functions for tracking events
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
    [ch
     upstream
     aborting?
     writable?
     last-write
     event-lock
     state-queue
     message-queue])

(defn- initial-state
  [ch]
  (State.
   ch              ;; ch
   nil             ;; upstream
   false           ;; aborting?
   true            ;; writable?
   nil             ;; last-write
   (atom true)     ;; event-lock
   (LinkedList.)   ;; state-queue
   (LinkedList.))) ;; message-queue

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
    (receive
     (.last-write current-state)
     (fn [_ _ _]
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
      (and (or (.. current-state ch isReadable)
               (not (.. current-state ch isOpen)))
           (.. current-state message-queue poll))))

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
  [state upstream]
  (let [current-state @state
        writable?     (.writable? current-state)
        ch            (.ch current-state)]
    (when (not= writable? (.isWritable ch))
      (swap! state #(assoc % :writable? (not writable?)))
      (upstream (if writable? :pause :resume) nil))))

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
            (if (= :interest-ops evt)
              (handle-interest-ops state upstream)
              (upstream evt val))
            (recur)))
        (catch Exception err
          (handle-err state err @state true)
          (abandon-lock current-state))))))

(defn- handle-err
  ([state err current-state]
     (handle-err state err current-state false))
  ([state err current-state locked?]
     (when (and current-state
                (not (.aborting? current-state)))
       (let [upstream  (.upstream current-state)]
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
         (let [val        (encode val)
               ch         (.ch current-state)
               last-write (.write ch val)]
           (swap! state #(assoc % :last-write last-write))))

       (= :close evt)
       (close-channel current-state)

       (= :pause evt)
       (when (.upstream current-state)
         (.setReadable (.ch current-state) false))

       (= :resume evt)
       (when (.upstream current-state)
         (.setReadable (.ch current-state) true))

       (= :abort evt)
       (handle-err state val current-state)

       :else
       (throw (Exception. "Unexpected event: " evt))))))

(defn mk-upstream-handler
  [^ChannelGroup channel-group app opts]
  (let [state (atom nil)]
    (reify ChannelUpstreamHandler
      (^void handleUpstream [_ ^ChannelHandlerContext ctx ^ChannelEvent evt]
        (let [current-state ^State @state]
          (try
            (cond
             ;; Handle message events
             (message-event? evt)
             (let [[evt val] (decode (.getMessage ^MessageEvent evt))]
               (send-upstream state evt val current-state))

             (interest-changed-event? evt)
             (send-upstream state :interest-ops nil current-state)

             ;; The connection has been established. Bind the application function
             ;; with a downstream and set the initial state. The
             ;; downstream should not be invoked during the binding
             ;; phase, so an open event will be fired.
             (channel-connected-event? evt)
             (let [ch (.getChannel evt)]
               ;; First, track the channel in the channel group
               (.add channel-group ch)
               ;; Now initialize the state with the channel
               (reset! state (initial-state ch))
               (let [upstream (app (mk-downstream-fn state))]
                 (swap! state #(assoc % :upstream upstream))
                 (send-upstream state :open (channel-info ch) @state)))

             (channel-disconnected-event? evt)
             (send-upstream state :close nil current-state)

             (exception-event? evt)
             (handle-err state (.getCause evt) current-state))
            (catch Exception err
              (handle-err state err @state))))))))
