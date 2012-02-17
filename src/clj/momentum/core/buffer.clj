(ns momentum.core.buffer
  (:import
   [momentum.buffer
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
  (^{:private true} to-buffer [_]))

(extend-protocol Conversion
  (class (byte-array 0))
  (to-buffer [bytes] (Buffer/wrap bytes))

  Buffer
  (to-buffer [buf] buf)

  ByteBuffer
  (to-buffer [buf] (Buffer/wrap buf))

  ChannelBuffer
  (to-buffer [buf] (Buffer/wrap buf))

  Collection
  (to-buffer [coll] (Buffer/wrap coll))

  String
  (to-buffer [str] (Buffer/wrap str))

  Number
  (to-buffer [n] (Buffer/allocate n))

  nil
  (to-buffer [_] nil))

(defprotocol Manipulation
  (into-buffer* [_ buf type-f]))

(extend-protocol Manipulation
  (class (byte-array 0))
  (into-buffer* [arr dst _]
    (.put dst arr))

  Buffer
  (into-buffer* [src dst _]
    (.put dst src (.position src) (.remaining src)))

  Collection
  (into-buffer* [coll dst type-f]
    (doseq [src coll]
      (into-buffer* src dst type-f)))

  Number
  (into-buffer* [n dst type-f]
    (if type-f
      (type-f dst n)
      (into-buffer* (Buffer/wrap n) dst nil)))

  String
  (into-buffer* [str dst _]
    (.put dst (.getBytes str "UTF-8")))

  nil
  (into-buffer* [_ dst _] dst)

  Object
  (into-buffer* [o dst _]
    (.put dst (Buffer/wrap o))))

(defn buffer*
  ([]    (dynamic-buffer))
  ([val] (to-buffer val)))

(def buffer-typed-writers
  {:byte    `write-byte
   :ubyte   `write-ubyte
   :short   `write-short
   :ushort  `write-ushort
   :int     `write-int
   :uint    `write-uint
   :long    `write-long
   :default `write})

(defmacro write-typed
  ([buf arg]
     (if (keyword? arg)
       buf
       (throw (Exception. "Invalid use of macro write-typed"))))

  ([buf type arg & rest]
     (if (keyword? arg)
       `(write-typed ~buf ~arg ~@rest)
       (if-let [type-sym (buffer-typed-writers type)]
         `(let [buf# ~buf]
            (into-buffer* ~arg buf# ~type-sym)
            (write-typed buf# ~type ~@rest))
         (throw (Exception. (str "Unknown buffer type: " type)))))))

(defmacro buffer
  "Convert arguments to a buffer"
  ([] `(dynamic-buffer))
  ([int-or-buf] `(buffer* ~int-or-buf))
  ([int-or-buf & args]
     (cond
      (keyword? int-or-buf)
      `(-> (dynamic-buffer 64)
           (write-typed ~int-or-buf ~@args)
           (flip)
           (slice))

      (number? int-or-buf)
      `(-> (buffer* ~int-or-buf)
           (write-typed :default ~@args)
           (flip))

      :else
      `(let [one# ~int-or-buf]
         (if (number? one#)
           (-> (buffer* one#)
               (write-typed :default ~@args)
               (flip))
           (-> (dynamic-buffer 64)
               (write-typed :default one# ~@args)
               (flip)
               (slice)))))))

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

(defn dynamic-buffer
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

(def EMPTY (buffer ""))

(defn to-string
  ([buf] (to-string buf "UTF-8"))
  ([buf ^String encoding]
     (cond
      (string? buf)
      buf

      (number? buf)
      (str buf)

      buf
      (.toString (buffer buf) encoding)

      :else
      (.toString EMPTY encoding))))

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
  [dst & srcs]
  (into-buffer* srcs dst nil))

(defn write-byte
  [buf b]
  (.put buf (byte b)))

(defn write-ubyte
  [buf b]
  (.putUnsigned buf b))

(defn write-short
  [buf s]
  (.putShort buf s))

(defn write-ushort
  [buf s]
  (.putShortUnsigned buf s))

(defn write-int
  [buf i]
  (.putInt buf i))

(defn write-uint
  [buf i]
  (.putIntUnsigned buf i))

(defn write-long
  [buf l]
  (.putLong buf l))

;; ==== Misc helpers

(defn KB
  [kilobytes]
  (* 1024 kilobytes))

(defn MB
  [megabytes]
  (* 1024 1024 megabytes))
