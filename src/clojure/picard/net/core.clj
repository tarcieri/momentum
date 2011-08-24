(ns picard.net.core
  (:use
   picard.core.deferred
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

;; ==== Conversions

(defn to-channel-buffer
  [obj]
  (cond
   (instance? ChannelBuffer obj)
   obj

   (instance? String obj)
   (ChannelBuffers/wrappedBuffer (.getBytes ^String obj))

   :else
   (throw (Exception. (str "Cannot convert " obj " to a ChannelBuffer")))))

;; ==== Helper functions for tracking events
(defn channel-open-event?
  [^ChannelStateEvent evt]
  (and (instance? ChannelStateEvent evt)
       (= ChannelState/OPEN (.getState evt))
       (.getValue evt)))

(defn channel-close-event?
  [^ChannelStateEvent evt]
  (and (instance? ChannelStateEvent evt)
       (= ChannelState/OPEN (.getState evt))
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

(defn mk-channel-tracker
  [^ChannelGroup channel-group]
  (reify ChannelUpstreamHandler
    (^void handleUpstream [_ ^ChannelHandlerContext ctx ^ChannelEvent evt]
      (when (channel-open-event? evt)
        (.add channel-group (.getChannel evt)))
      (.sendUpstream ctx evt))))

(defrecord State
    [ch
     upstream
     aborting?
     writable?
     last-write
     event-lock
     event-queue])

(defn- initial-state
  [ch]
  (State.
   ch    ;; ch
   nil   ;; upstream
   false ;; aborting?
   true  ;; writable?
   nil   ;; last-write
   (atom true)
   (LinkedList.)))

(declare handle-err)

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

(defn- handle-interest-ops
  [state upstream]
  (let [current-state @state
        writable?     (.writable? current-state)
        ch            (.ch current-state)]
    (when (not= writable? (.isWritable ch))
      (swap! state #(assoc % :writable? (not writable?)))
      (upstream (if writable? :pause :resume) nil))))

(defn- send-upstream
  [state evt val current-state]
  (let [event-lock  (.event-lock current-state)
        event-queue (.event-queue current-state)
        acquired?   (try-acquire event-lock event-queue evt val)]
    (when acquired?
      (try
        (loop [evt evt val val]
          (let [upstream (.upstream current-state)]
            (if (= :interest-ops evt)
              (handle-interest-ops state upstream)
              (upstream evt val)))
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
       (when (.upstream current-state)
         (.setReadable (.ch current-state) false))

       (= :resume evt)
       (when (.upstream current-state)
         (.setReadable (.ch current-state) true))

       (= :abort evt)
       (handle-err state val current-state)))))

(defn mk-upstream-handler
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

             (interest-changed-event? evt)
             (send-upstream state :interest-ops nil current-state)

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
