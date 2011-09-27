(ns picard.utils.conversions
  (:import
   [java.nio
    ByteBuffer]
   [org.jboss.netty.buffer
    ChannelBuffer]))

(defprotocol Conversions
  (^ByteBuffer to-byte-buffer [_])
  (^"[B" to-byte-array [_]))

(extend-protocol Conversions
  (class (byte-array 0))
  (to-byte-buffer [bytes]
    (ByteBuffer/wrap bytes))
  (to-byte-array [bytes]
    bytes)

  ByteBuffer
  (to-byte-buffer [buf]
    buf)
  (to-byte-array [buf]
    (let [arr (byte-array (.remaining buf))]
      (.get buf arr)
      arr))

  ChannelBuffer
  (to-byte-buffer [buf]
    (.toByteBuffer buf))
  (to-byte-array [buf]
    (let [arr (byte-array (.readableBytes buf))]
      (.readBytes arr)
      arr))

  String
  (to-byte-buffer [str]
    (ByteBuffer/wrap (.getBytes str)))
  (to-byte-array [str]
    (.getBytes str)))
