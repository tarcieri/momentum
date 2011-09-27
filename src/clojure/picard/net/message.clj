(ns picard.net.message
  (:import
   [java.nio
    ByteBuffer]
   [org.jboss.netty.buffer
    ChannelBuffer
    ChannelBuffers]))

(defprotocol DecodeMessage
  (decode [msg]))

(defprotocol EncodeMessage
  (encode [msg]))

(defprotocol Conversions
  (to-channel-buffer [_]))

(extend-protocol DecodeMessage
  clojure.lang.PersistentVector
  (decode [msg] msg)

  Object
  (decode [msg] [:message msg])

  nil
  (decode [_] [:message nil]))

(extend-protocol EncodeMessage
  ByteBuffer
  (encode [buf] (ChannelBuffers/wrappedBuffer buf))

  String
  (encode [msg] (to-channel-buffer msg))

  Object
  (encode [msg] msg)

  nil
  (encode [_] nil))

(extend-protocol Conversions
  ChannelBuffer
  (to-channel-buffer [buf]
    buf)

  String
  (to-channel-buffer [str]
    (ChannelBuffers/wrappedBuffer (.getBytes str))))
