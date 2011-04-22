(ns picard.formats
  (:import
   [java.nio
    ByteBuffer]
   [org.jboss.netty.buffer
    ChannelBuffer
    ChannelBuffers]))

(defn string->byte-buffer
  ([s]
     (string->byte-buffer s "UTF-8"))
  ([s charset]
     (when s
       (ByteBuffer/wrap (.getBytes ^String s ^String charset)))))

(defn string->channel-buffer
  ([s]
     (string->channel-buffer s "UTF-8"))
  ([s charset]
     (when s
       (ChannelBuffers/wrappedBuffer (.getBytes ^String s ^String charset)))))
