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
 dynamic-buffer
 flip
 remaining
 remaining?
 slice
 transfer!
 write)

(defprotocol Conversion
  (^{:private true} estimate [_])
  (to-buffer [_]))

(extend-protocol Conversion
  (class (byte-array 0))
  (estimate  [bytes] (count bytes))
  (to-buffer [bytes] (Buffer/wrap bytes))

  Buffer
  (estimate  [buf] (.remaining buf))
  (to-buffer [buf] buf)

  ByteBuffer
  (estimate  [buf] (.remaining buf))
  (to-buffer [buf] (Buffer/wrap buf))

  ChannelBuffer
  (estimate  [buf] (.readableBytes buf))
  (to-buffer [buf] (Buffer/wrap buf))

  Collection
  (estimate  [coll]
    (/ (* (count coll)
          (+ (estimate (first coll))
             (estimate (last coll)))) 2))
  (to-buffer [coll] (Buffer/wrap coll))

  String
  (estimate  [str] (* 2 (.length str)))
  (to-buffer [str] (Buffer/wrap str))

  Number
  (estimate  [_] 8)
  (to-buffer [n] (Buffer/allocate n))

  nil
  (estimate  [_] 0)
  (to-buffer [_] nil))

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
       (doto (buffer int-or-buf)
         (write bufs)
         (flip))

       (let [buf (dynamic-buffer (max (* 2 (estimate int-or-buf)) 64))]
         (write buf int-or-buf)
         (write buf bufs)
         (flip buf)
         (slice buf)))))

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

;; (defn freeze
;;   [^Buffer buf]
;;   (.freeze buf))

;; (defn frozen?
;;   [^Buffer buf]
;;   (.isFrozen buf))

;; ;; Temporary
;; (def frozen frozen?)

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

(defn slice
  ([buf]         (.slice buf))
  ([buf idx len] (.slice buf idx len)))

(defn to-byte-array
  [^Buffer buf]
  (.toByteArray buf))

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
  (Buffer/wrap bufs))

(defn write
  [^Buffer dst & srcs]
  (put srcs dst))
