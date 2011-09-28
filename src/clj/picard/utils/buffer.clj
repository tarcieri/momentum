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
 transfer!
 remaining)

(defprotocol Buffer
  (to-buffer [_])
  (to-bytes [_]))

(extend-protocol Buffer
  (class (byte-array 0))
  (to-buffer [bytes]
    (ByteBuffer/wrap bytes))
  (to-bytes [bytes]
    bytes)

  ByteBuffer
  (to-buffer [buf] buf)
  (to-bytes [buf]
    (let [arr (byte-array (.remaining buf))]
      (.get buf arr)
      arr))

  ChannelBuffer
  (to-buffer [buf]
    (.toByteBuffer buf))
  (to-bytes [buf]
    (let [arr (byte-array (.readableBytes buf))]
      (.readBytes buf arr)
      arr))

  String
  (to-buffer [str]
    (ByteBuffer/wrap (.getBytes str)))
  (to-bytes [str]
    (.getBytes str))

  nil
  (to-buffer [_] nil)
  (to-bytes [_] nil)

  Object
  (to-buffer [o]
    (to-buffer (str o)))
  (to-bytes [o]
    (to-bytes (str o))))

(defn buffer
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

(defn remaining
  [^ByteBuffer buf]
  (.remaining buf))

(defn remaining?
  [^ByteBuffer buf]
  (.hasRemaining buf))

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
  (ChannelBuffers/wrappedBuffer
   (into-array
    (map to-buffer bufs))))

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
