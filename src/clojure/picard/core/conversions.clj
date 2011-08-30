(ns picard.core.conversions
  (:import
   [org.jboss.netty.buffer
    ChannelBuffer
    ChannelBuffers]))

(defprotocol Conversions
  (to-channel-buffer [_]))

(extend-protocol Conversions
  ChannelBuffer
  (to-channel-buffer [buf]
    buf)
  String
  (to-channel-buffer [str]
    (ChannelBuffers/wrappedBuffer (.getBytes str))))
