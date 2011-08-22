(ns picard.net.core
  (:use
   picard.core.deferred)
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
