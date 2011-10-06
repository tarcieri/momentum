(ns picard.utils.buffer
  (:import
   [java.nio
    ByteBuffer]
   [org.jboss.netty.buffer
    ChannelBuffer
    ChannelBuffers]))

(declare
 flip
 transfer
 transfer!)

(defprotocol Conversions
  (to-byte-buffer [_])
  (to-channel-buffer [_])
  (to-buffer [_])
  (to-bytes [_]))

(extend-protocol Conversions
  (class (byte-array 0))
  (to-byte-buffer [bytes]
    (ByteBuffer/wrap bytes))
  (to-channel-buffer [bytes]
    (ChannelBuffers/wrappedBuffer bytes))
  (to-buffer [bytes]
    (ByteBuffer/wrap bytes))
  (to-bytes [bytes]
    bytes)

  ByteBuffer
  (to-byte-buffer [buf]
    buf)
  (to-channel-buffer [buf]
    (ChannelBuffers/wrappedBuffer buf))
  (to-buffer [buf]
    buf)
  (to-bytes [buf]
    (let [arr (byte-array (.remaining buf))]
      (.get buf arr)
      arr))

  ChannelBuffer
  (to-byte-buffer [buf]
    (.toByteBuffer buf))
  (to-channel-buffer [buf]
    buf)
  (to-buffer [buf]
    buf)
  (to-bytes [buf]
    (let [arr (byte-array (.readableBytes buf))]
      (.readBytes buf arr)
      arr))

  Iterable
  (to-byte-buffer [list]
    (to-byte-buffer (to-channel-buffer list)))
  (to-channel-buffer [list]
    (ChannelBuffers/wrappedBuffer
     (into-array
      (map to-channel-buffer list))))
  (to-buffer [list]
    (to-channel-buffer list))
  (to-bytes [list]
    (to-bytes (to-channel-buffer list)))

  String
  (to-byte-buffer [str]
    (ByteBuffer/wrap (to-bytes str)))
  (to-channel-buffer [str]
    (ChannelBuffers/wrappedBuffer (to-bytes str)))
  (to-buffer [str]
    (to-byte-buffer str))
  (to-bytes [str]
    (.getBytes str "UTF-8"))

  nil
  (to-byte-buffer [_]
    nil)
  (to-channel-buffer [_]
    nil)
  (to-buffer [_]
    nil)
  (to-bytes [_]
    nil)

  Object
  (to-byte-buffer [o]
    (to-byte-buffer (str o)))
  (to-channel-buffer [o]
    (to-channel-buffer (str o)))
  (to-buffer [o]
    (to-buffer (str o)))
  (to-bytes [o]
    (to-bytes (str o))))

(defn to-string
  ([buf]
     (to-string buf "UTF-8"))
  ([buf encoding]
     (when-let [bytes (to-bytes buf)]
       (String. bytes encoding))))

(defprotocol Buffer
  (remaining  [_])
  (remaining? [_]))

(extend-protocol Buffer
  ByteBuffer
  (remaining [this]
    (.remaining this))
  (remaining? [this]
    (.hasRemaining this))

  ChannelBuffer
  (remaining [this]
    (.readableBytes this))
  (remaining? [this]
    (.readable this)))

(defn ^ByteBuffer buffer
  ([]
     (ByteBuffer/allocate 1024))

  ([int-or-buf]
     (if (number? int-or-buf)
       (ByteBuffer/allocate int-or-buf)
       (to-buffer int-or-buf)))

  ([int-or-buf & bufs]
     (if (number? int-or-buf)

       ;; Specific buffer size, allocate and transfer
       (let [buf (ByteBuffer/allocate int-or-buf)]
         (doseq [src bufs]
           (transfer! (to-buffer src) buf))
         (flip buf))

       ;; We just have buffers, so calculate the buffer size then
       ;; transfer
       (let [bufs (map to-buffer (concat [int-or-buf] bufs))]
         (apply
          buffer
          (reduce (fn [size buf] (+ size (remaining buf))) 0 bufs)
          bufs)))))

(defn capacity
  [^ByteBuffer buf]
  (.capacity buf))

(defn collapsed?
  [^ByteBuffer buf]
  (= (.position buf) (.limit buf)))

(defn flip
  [^ByteBuffer buf]
  (.flip buf))

(defn focus
  [^ByteBuffer buf size]
  (.limit buf (+ size (.position buf))))

(defn holds?
  [^ByteBuffer dst ^ByteBuffer src]
  (>= (.remaining dst)
      (.remaining src)))

(defn limit
  [^ByteBuffer buf]
  (.limit buf))

(defn position
  [^ByteBuffer buf]
  (.position buf))

(defn rewind
  [^ByteBuffer buf]
  (.rewind buf))

(defn transfer
  "Transfers the contents of one buffer to the other. If the
  destionation buffer is not able to contain the source buffer, then
  as much as possible is transfered."
  [^ByteBuffer src ^ByteBuffer dst]
  (if (holds? dst src)
    (transfer! src dst)
    (let [src-limit (.limit src)]
      (focus src (.remaining dst))
      (transfer! src dst)
      (.limit src src-limit)
      false)))

(defn transfer!
  [^ByteBuffer src ^ByteBuffer dst]
  (.put dst src)
  true)

(defn wrap
  [& bufs]
  (to-buffer bufs))

(defn batch
  "Takes an initial buffer size, a function that creates the buffers
  to batch and a function to received the batched buffers.

  For example:

  (batch
   512 ;; Initially allocate a buffer of 512 bytes
   (fn [b]
     (b \"Hello\")
     (b \"World\"))
   (fn [buf]
     buf ;; Is HelloWorld
     )) "
  ([builder callback]
     (batch 4096 builder callback))

  ([size builder callback]
     (let [state (atom (buffer size))]
       (builder
        (fn batcher
          ([data]
             (let [data (to-buffer data)]
               (loop [buf @state]
                 (when (remaining? data)
                   (if (transfer data buf)
                     (recur buf)
                     (do
                       (-> buf flip callback)
                       (recur (reset! state (buffer size)))))))))
          ([data & rest]
             (batcher data)
             (doseq [data rest]
               (batcher data)))))

       ;; Send off the last buffer
       (let [buf @state]
         (flip buf)
         (when-not (collapsed? buf)
           (callback buf))))))
