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
 transfer!)

(defprotocol Conversion
  (^Buffer to-buffer [_]))

(extend-protocol Conversion
  (class (byte-array 0))
  (to-buffer [bytes]
    (Buffer/wrap bytes))

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

(defn ^Buffer buffer
  ([] (Buffer/allocate 1024))
  ([val] (to-buffer val))
  ([int-or-buf & bufs]
     (if (number? int-or-buf)
       (let [buf (Buffer/allocate int-or-buf)]
         (doseq [src bufs]
           (transfer! (to-buffer src) buf))
         (flip buf))
       (throw (Exception. "Not implemented")))))

(defn capacity
  [^Buffer buf]
  (.capacity buf))

(defn collapsed?
  [^Buffer buf]
  (not (.hasRemaining buf)))

(defn direct-buffer
  [size]
  (Buffer/allocateDirect size))

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

(defn frozen
  [^Buffer buf]
  (.isFrozen buf))

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

(defn reset
  [^Buffer buf]
  (.reset buf))

(defn rewind
  [^Buffer buf]
  (.rewind buf))

(defn transfer!
  [^Buffer src ^Buffer dst]
  (.put dst src))

(defn transfer
  [^Buffer src ^Buffer dst]
  (if (holds? dst src)
    (transfer! src dst)
    (let [src-limit (limit src)]
      (focus src (remaining dst))
      (limit src src-limit)
      false)))

(defn wrap
  [& bufs]
  (to-buffer bufs))
