(ns picard.net.core
  (:import
   [org.jboss.netty.channel
    Channels
    ChannelEvent
    ChannelHandlerContext
    ChannelState
    ChannelStateEvent
    ChannelUpstreamHandler
    ExceptionEvent
    MessageEvent]
   [org.jboss.netty.channel.group
    ChannelGroup
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

;; Helper functions for tracking events
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

;; Some handlers
(defn mk-channel-tracker
  [^ChannelGroup channel-group]
  (reify ChannelUpstreamHandler
    (^void handleUpstream [_ ^ChannelHandlerContext ctx ^ChannelEvent evt]
      (when (channel-open-event? evt)
        (.add channel-group (.getChannel evt)))
      (.sendUpstream ctx evt))))
