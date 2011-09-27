(ns picard.utils.buffer
  (:use
   picard.utils.conversions)
  (:import
   [java.nio
    ByteBuffer]))

(defn allocate
  [size]
  (ByteBuffer/allocate size))

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

(defn transfer!
  [^ByteBuffer src ^ByteBuffer dst]
  (.put dst src)
  true)

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
     (let [state (atom (allocate size))]
       (builder
        (fn batcher
          ([data]
             (let [data (to-byte-buffer data)]
               (loop [buf @state]
                 (when (remaining? data)
                   (if (transfer data buf)
                     (recur buf)
                     (do
                       (-> buf flip callback)
                       (recur (reset! state (allocate size)))))))))
          ([data & rest]
             (batcher data)
             (doseq [data rest]
               (batcher data)))))

       ;; Send off the last buffer
       (let [buf @state]
         (flip buf)
         (when-not (collapsed? buf)
           (callback buf))))))
