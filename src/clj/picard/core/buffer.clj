(ns picard.core.buffer
  (:import
   [picard.core
    Buffer]
   [java.nio
    ByteBuffer]
   [java.util
    Collection]
   [org.jboss.netty.buffer
    ChannelBuffer
    ChannelBuffers]))

(declare
 flip
 remaining
 remaining?
 transfer!
 write)

(defprotocol Conversion
  (^Buffer to-buffer [_]))

(extend-protocol Conversion
  (class (byte-array 0))
  (to-buffer [bytes]
    (Buffer/wrap bytes))

  Buffer
  (to-buffer [buf]
    buf)

  ByteBuffer
  (to-buffer [buf]
    (Buffer/wrap buf))

  ChannelBuffer
  (to-buffer [buf]
    (Buffer/wrap buf))

  Collection
  (to-buffer [coll]
    (Buffer/wrap coll))

  String
  (to-buffer [str]
    (Buffer/wrap (.getBytes str "UTF-8")))

  Number
  (to-buffer [num]
    (Buffer/allocate num))

  nil
  (to-buffer [_]
    nil))

(defprotocol Manipulation
  (^{:private true} put [_ buf]))

(extend-protocol Manipulation
  (class (byte-array 0))
  (put [arr dst]
    (.put dst arr))

  Buffer
  (put [src dst]
    (.put dst src (.position src) (.remaining src))
    dst)

  Collection
  (put [coll dst]
    (doseq [src coll]
      (put src dst))
    dst)

  String
  (put [str dst]
    (.put dst (.getBytes str "UTF-8"))
    dst)

  nil
  (put [_ dst] dst)

  Object
  (put [o dst]
    (.put dst (.getBytes (.toString o) "UTF-8"))
    dst))

(defn ^Buffer buffer
  ([] (Buffer/allocate 1024))
  ([val] (to-buffer val))
  ([int-or-buf & bufs]
     (if (number? int-or-buf)
       (doto (Buffer/allocate int-or-buf)
         (write bufs)
         (flip))

       (let [bufs (map to-buffer (concat [int-or-buf] bufs))]
         (doto (Buffer/allocate (reduce #(+ %1 (remaining %2)) 0 bufs))
           (write bufs)
           (flip))))))

(defn buffer?
  [maybe-buffer]
  (instance? Buffer maybe-buffer))

(defn capacity
  [buf]
  (.capacity buf))

(defn collapsed?
  [buf]
  (not (remaining? buf)))

(defn direct-buffer
  [size]
  (Buffer/allocateDirect size))

(defn ^Buffer dynamic-buffer
  ([]        (Buffer/dynamic))
  ([est]     (Buffer/dynamic est))
  ([est max] (Buffer/dynamic est max)))

(defn duplicate
  [^Buffer buf]
  (.duplicate buf))

(defn flip
  [^Buffer buf]
  (.flip buf))

(defn focus
  [^Buffer buf size]
  (.limit buf (+ size (.position buf))))

(defn freeze
  [^Buffer buf]
  (.freeze buf))

(defn frozen?
  [^Buffer buf]
  (.isFrozen buf))

;; Temporary
(def frozen frozen?)

(defn holds?
  [^Buffer dst ^Buffer src]
  (>= (.remaining dst)
      (.remaining src)))

(defn limit
  ([^Buffer buf]
     (.limit buf))
  ([^Buffer buf val]
     (.limit buf val)))

(defn position
  ([^Buffer buf]
     (.position buf))
  ([^Buffer buf val]
     (.position buf val)))

(defn remaining
  [buf]
  (.remaining buf))

(defn remaining?
  [buf]
  (.hasRemaining buf))

(defn reset
  [^Buffer buf]
  (.reset buf))

(defn rewind
  [^Buffer buf]
  (.rewind buf))

(defn to-channel-buffer
  [^Buffer buf]
  (.toChannelBuffer buf))

(defn to-string
  ([^Buffer buf]
     (.toString buf "UTF-8"))
  ([^Buffer buf ^String encoding]
     (.toString buf encoding)))

;; TODO: Revisit the transfer helper
(defn transfer!
  [^Buffer src ^Buffer dst]
  (.put dst src))

(defn transfer
  ([^Buffer src ^Buffer dst]
     (if (holds? dst src)
       (transfer! src dst)
       (let [src-limit (limit src)]
         (focus src (remaining dst))
         (limit src src-limit)
         false))))

(defn wrap
  [& bufs]
  (to-buffer bufs))

(defn write
  [^Buffer dst & srcs]
  (put srcs dst))
