(ns picard.test.core.buffer
  (:use
   clojure.test)
  (:import
   java.nio.ByteBuffer
   java.nio.ByteOrder
   java.util.Arrays
   picard.core.Buffer))

(def d-at-50
  (doto (byte-array 100)
    (aset-byte 50 (byte 100))))

(def F-at-60
  (doto (byte-array 100)
    (aset-byte 60 (byte 70))))

(defn- test-buffer
  [buf]
  ;; Basics
  (is (= 100 (.limit buf)))
  (is (= 100 (.capacity buf)))
  (is (= 0 (.position buf)))

  ;; Absolute get / put
  (.put buf 50 (byte 100))
  (is (= 100 (.get buf 50)))
  (let [arr (byte-array 100)]
    (.get buf 0 arr 0 100)
    (is (Arrays/equals arr d-at-50)))

  (.put buf 0 F-at-60 0 100)
  (is (= 0 (.get buf 50)))
  (is (= 70 (.get buf 60)))

  (.put buf 0 d-at-50 0 40)
  (is (= 0 (.get buf 50)))
  (is (= 70 (.get buf 60)))

  ;; Relative single byte get / put
  (.put buf (byte 100))
  (is (= 1 (.position buf)))
  (is (= 100 (.limit buf)))
  (is (= 100 (.capacity buf)))

  (.put buf 0 d-at-50 0 100)

  ;; Accessor: CHAR

  (is (= (char 100) (.getChar buf 49)))
  (is (= (char 100) (.getCharBigEndian buf 49)))

  (.order buf (ByteOrder/LITTLE_ENDIAN))

  (is (= (char 100) (.getChar buf 50)))
  (is (= (char 100) (.getCharLittleEndian buf 50)))

  (.order buf (ByteOrder/BIG_ENDIAN))

  (.putChar buf 49 (char 100))
  (is (= 0 (.get buf 49)))
  (is (= 100 (.get buf 50)))

  (.order buf (ByteOrder/LITTLE_ENDIAN))

  (.putChar buf 49 (char 100))
  (is (= 100 (.get buf 49)))
  (is (= 0 (.get buf 50)))

  (.order buf (ByteOrder/BIG_ENDIAN))

  (.clear buf)

  (.putChar buf (char 258))
  (is (= 2 (.position buf)))
  (is (= 1 (.get buf 0)))
  (is (= 2 (.get buf 1)))

  (.order buf (ByteOrder/LITTLE_ENDIAN))

  (.putChar buf (char 258))
  (is (= 4 (.position buf)))
  (is (= 2 (.get buf 2)))
  (is (= 1 (.get buf 3)))

  ;; Exceptional cases
  (is (thrown? IndexOutOfBoundsException (.get buf -1)))
  (is (thrown? IndexOutOfBoundsException (.get buf 100))))

(deftest heap-allocated-buffers
  (test-buffer (Buffer/allocate 100)))

(deftest byte-buffer-backed-buffers
  (test-buffer (Buffer/wrap (ByteBuffer/allocate 100))))
