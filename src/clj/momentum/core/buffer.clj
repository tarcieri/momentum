(ns ^{:author "Carl Lerche"
      :doc
      "Provides an abstraction around byte arrays similar to Java's
       ByteBuffer.

       # Overview

       A buffer provides a linear, finite sequence of bytes, that is
       accessible both randomly and sequentially, backed by one of a
       number of possible data backends with various properties. The
       possible buffer backends include, byte[], NIO buffers, and a
       dynamic byte list.

       Just like with Java buffers, a momentum buffer tracks the
       content of the buffer, total capacity, limit, and position. The
       _capacity_ of a buffer is the number of bytes it may contain.
       This value is never negative and never changes. The _limit_ of
       a buffer is the index of the first byte that should not be read
       or written to. This value is never negative and never is
       greater than the capacity. The _position_ of a buffer is the
       index of the next byte to read or write to. This value is
       positive and less than or equal to the limit.

       # Dynamic Buffers

       A dynamic buffer is a buffer that is capable of growing in
       capacity. This is achieved by backing the dynamic buffer with
       an array of buffers (usually byte array babcked buffers, but
       there is no reason why a dynamic buffer can't be backed by
       another dynamic buffer).

       Once created, they should be interacted with no differently
       than any other buffer.

       # Creation

       The `momentum.core.buffer/buffer` macro is a convinient helper
       to create a new buffer or convert existing types into a buffer.
       A dynamic buffer can be explicitly created with
       `momentum.core.buffer/dynamic-buffer`.

       # Random Access

       Buffers use zero-based indexing (same as arrays). The first
       byte in the buffer is at offset 0 and the last byte in the
       buffer is at length - 1. Any index can be read, regardless of
       the current position and limit, by using the absolute getters.
       Similarly, any index can be written to by using the absolute
       setters.

       # Sequential Access

       Buffers provide sequential access via a limit index and a
       position index exactly the same way

       # Transient Buffers

       # Converting From Java Types

       # Converting To Java Types"} momentum.core.buffer
  (:import
   [momentum.buffer
    Buffer]
   [java.nio
    ByteBuffer]
   [java.util
    Collection]))

(declare
 dynamic-buffer
 flip
 remaining
 remaining?
 slice
 transfer!
 write)

(defprotocol Conversion
  (^{:private true} ^Buffer to-buffer [_]))

(extend-protocol Conversion
  (class (byte-array 0))
  (to-buffer [bytes] (Buffer/wrap bytes))

  Buffer
  (to-buffer [buf] buf)

  ByteBuffer
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
    (.put ^Buffer dst ^bytes arr))

  Buffer
  (into-buffer* [src dst _]
    (.put ^Buffer dst src (.position src) (.remaining src)))

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
    (.put ^Buffer dst (.getBytes str "UTF-8")))

  nil
  (into-buffer* [_ dst _] dst)

  Object
  (into-buffer* [o dst _]
    (.put ^Buffer dst (Buffer/wrap o))))

(defn ^Buffer buffer*
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
  [^Buffer buf] (.capacity buf))

(defn clear
  [^Buffer buf] (.clear buf))

(defn collapsed?
  [^Buffer buf] (not (remaining? buf)))

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

(defn holds?
  [^Buffer dst ^Buffer src]
  (>= (.remaining dst)
      (.remaining src)))

(defn limit
  ([^Buffer buf]     (.limit buf))
  ([^Buffer buf val] (.limit buf val)))

(defn position
  ([^Buffer buf]     (.position buf))
  ([^Buffer buf val] (.position buf val)))

(defn remaining
  [^Buffer buf] (and buf (.remaining buf)))

(defn remaining?
  [^Buffer buf] (and buf (.hasRemaining buf)))

(defn retain
  [^Buffer buf]
  (if (buffer? buf)
    (.retain buf)
    buf))

(defn rewind
  [^Buffer buf] (.rewind buf))

(defn slice
  ([^Buffer buf]         (.slice buf))
  ([^Buffer buf idx len] (.slice buf idx len)))

(defn transient!
  [^Buffer buf] (.makeTransient buf))

(defn transient?
  [^Buffer buf] (.isTransient buf))

(defn to-byte-array
  [^Buffer buf] (.toByteArray buf))

(def EMPTY (buffer ""))

(defn to-string
  ([buf] (to-string buf "UTF-8"))
  ([^Buffer buf ^String encoding]
     (cond
      (string? buf)
      buf

      (number? buf)
      (str buf)

      buf
      (.toString (buffer buf) encoding)

      :else
      (.toString ^Buffer EMPTY encoding))))

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
  [^Buffer buf b]
  (.put buf (byte b)))

(defn write-ubyte
  [^Buffer buf b]
  (.putUnsigned buf b))

(defn write-short
  [^Buffer buf s]
  (.putShort buf s))

(defn write-ushort
  [^Buffer buf s]
  (.putShortUnsigned buf s))

(defn write-int
  [^Buffer buf i]
  (.putInt buf i))

(defn write-uint
  [^Buffer buf i]
  (.putIntUnsigned buf i))

(defn write-long
  [^Buffer buf l]
  (.putLong buf l))

;; ==== Misc helpers

(defn KB
  [kilobytes]
  (* 1024 kilobytes))

(defn MB
  [megabytes]
  (* 1024 1024 megabytes))
