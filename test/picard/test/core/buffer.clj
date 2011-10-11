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

(def increasing
  (let [arr (byte-array 100)]
    (dotimes [i 100]
      (aset-byte arr i (byte i)))
    arr))

(def decreasing
  (let [arr (byte-array 100)]
    (dotimes [i 100]
      (aset-byte arr i (byte (- 100 i))))
    arr))

(def a-double (double 0.019362822575641925))
(def a-long   (long   6254412272976093352))
(def a-float  (float  0.86666465))
(def a-int    (int    1199546451))
(def a-short  (short  1234))

(defn- mk-arr
  [size order f]
  (let [buf (ByteBuffer/allocate size)
        arr (byte-array size)]
    ;; Set the order
    (.order buf order)

    ;; Populate the buffer
    (loop []
      (f buf)
      (when (.hasRemaining buf)
        (recur)))
    (.array buf)))

(defn- get-arr
  [buf idx len]
  (let [arr (byte-array len)]
    (.get buf idx arr)
    arr))

(defn- in-steps-of
    [base step buf & fns]
    (dotimes [i step]
      (.put buf i base)

      (doseq [f fns]
        (.position buf i)

        (dotimes [j (/ 80 step)]
          (f (+ i (* step j)))))))

(defn- test-buffer
  [buf]
  ;; Basics
  (is (= 100 (.limit buf)))
  (is (= 100 (.capacity buf)))
  (is (= 0 (.position buf)))

  ;; Absolute single byte get / put
  (is (= 0 (.get buf 50)))
  (.put buf 50 (byte 100))
  (is (= 100 (.get buf 50)))
  (is (= 0 (.position buf)))

  ;; Absolute multibyte get / put
  (is (Arrays/equals (get-arr buf 0 100) d-at-50))

  (.put buf 0 F-at-60 0 100)
  (dotimes [i 60]
    (is (= 0 (.get buf i))))

  (is (= 70 (.get buf 60)))

  (dotimes [i 39]
    (is (= 0 (.get buf (+ 61 i)))))

  ;; Absolute multibyte put w/ length
  (.put buf 0 d-at-50 0 40)
  (is (= 0 (.get buf 50)))
  (is (= 70 (.get buf 60)))

  (.put buf 90 (byte 123))
  (.put buf 10 d-at-50 5 80)
  (is (= 123 (.get buf 90)))
  (is (= 100 (.get buf 55)))

  (.position buf 0)
  (.limit buf 100)

  ;; Relative single byte get / put

  (dotimes [i 100]
    (is (= i (.position buf)))
    (.put buf (byte i)))

  (is (= 100 (.position buf)))
  (is (= 100 (.limit buf)))
  (is (= 100 (.capacity buf)))

  (is (Arrays/equals (get-arr buf 0 100) increasing))

  (.flip buf)

  (is (= 0 (.position buf)))
  (is (= 100 (.limit buf)))

  (.put buf 0 decreasing 0 100)
  (dotimes [i 100]
    (is (= (.position buf i)))
    (is (= (- 100 i) (.get buf))))

  ;; Getting big endian chars
  (in-steps-of
   (mk-arr 80 ByteOrder/BIG_ENDIAN #(.putChar % (char 1234)))
   2 buf
   (fn [i]
     (is (= (char 1234) (.getChar buf)))
     (is (= (char 1234) (.getChar buf i))))
   (fn [i]
     (is (= (char 1234) (.getCharBigEndian buf)))
     (is (= (char 1234) (.getCharBigEndian buf i))))
   )

  (.order buf ByteOrder/LITTLE_ENDIAN)
  ;; Getting little endian chars

  (in-steps-of
   (mk-arr 80 ByteOrder/LITTLE_ENDIAN #(.putChar % (char 1234)))
   2 buf
   (fn [i]
     (is (= (char 1234) (.getChar buf)))
     (is (= (char 1234) (.getChar buf i))))
   (fn [i]
     (is (= (char 1234) (.getCharLittleEndian buf)))
     (is (= (char 1234) (.getCharLittleEndian buf i))))
   (fn [i]
     (.putChar buf (char 1234))
     (is (= (char 1234) (.getChar buf i)))
     (.putChar buf i (char 1234))
     (is (= (char 1234) (.getChar buf i))))
   (fn [i]
     (.putCharLittleEndian buf (char 1234))
     (is (= (char 1234) (.getChar buf i)))
     (.putCharLittleEndian buf i (char 1234))
     (is (= (char 1234) (.getChar buf i))))
   )

  (.order buf ByteOrder/BIG_ENDIAN)

  (in-steps-of
   (mk-arr 80 ByteOrder/BIG_ENDIAN #(.putLong % a-long))
   8 buf
   (fn [i]
     (is (= a-long (.getLong buf)))
     (is (= a-long (.getLong buf i))))
   (fn [i]
     (is (= a-long (.getLongBigEndian buf)))
     (is (= a-long (.getLongBigEndian buf i))))
   (fn [i]
     (.putLong buf a-long)
     (is (= a-long (.getLong buf i)))
     (.putLong buf i a-long)
     (is (= a-long (.getLong buf i))))
   (fn [i]
     (.putLongBigEndian buf a-long)
     (is (= a-long (.getLong buf i)))
     (.putLongBigEndian buf i a-long)
     (is (= a-long (.getLong buf i))))
   )

  (.order buf ByteOrder/LITTLE_ENDIAN)

  (in-steps-of
   (mk-arr 80 ByteOrder/LITTLE_ENDIAN #(.putLong % a-long))
   8 buf
   (fn [i]
     (is (= a-long (.getLong buf)))
     (is (= a-long (.getLong buf i))))
   (fn [i]
     (is (= a-long (.getLongLittleEndian buf)))
     (is (= a-long (.getLongLittleEndian buf i))))
   (fn [i]
     (.putLong buf a-long)
     (is (= a-long (.getLong buf i)))
     (.putLong buf i a-long)
     (is (= a-long (.getLong buf i))))
   (fn [i]
     (.putLongLittleEndian buf a-long)
     (is (= a-long (.getLong buf i)))
     (.putLongLittleEndian buf i a-long)
     (is (= a-long (.getLong buf i))))
   )

  (.order buf ByteOrder/BIG_ENDIAN)

  (in-steps-of
   (mk-arr 80 ByteOrder/BIG_ENDIAN #(.putDouble % a-double))
   8 buf
   (fn [i]
     (is (= a-double (.getDouble buf)))
     (is (= a-double (.getDouble buf i))))
   (fn [i]
     (is (= a-double (.getDoubleBigEndian buf)))
     (is (= a-double (.getDoubleBigEndian buf i))))
   (fn [i]
     (.putDouble buf a-double)
     (is (= a-double (.getDouble buf i)))
     (.putDouble buf i a-double)
     (is (= a-double (.getDouble buf i))))
   (fn [i]
     (.putDoubleBigEndian buf a-double)
     (is (= a-double (.getDouble buf i)))
     (.putDoubleBigEndian buf i a-double)
     (is (= a-double (.getDouble buf i)))))

  (.order buf ByteOrder/LITTLE_ENDIAN)

  (in-steps-of
   (mk-arr 80 ByteOrder/LITTLE_ENDIAN #(.putDouble % a-double))
   8 buf
   (fn [i]
     (is (= a-double (.getDouble buf)))
     (is (= a-double (.getDouble buf i))))
   (fn [i]
     (is (= a-double (.getDoubleLittleEndian buf)))
     (is (= a-double (.getDoubleLittleEndian buf i))))
   (fn [i]
     (.putDouble buf a-double)
     (is (= a-double (.getDouble buf i)))
     (.putDouble buf i a-double)
     (is (= a-double (.getDouble buf i))))
   (fn [i]
     (.putDoubleLittleEndian buf a-double)
     (is (= a-double (.getDouble buf i)))
     (.putDoubleLittleEndian buf i a-double)
     (is (= a-double (.getDouble buf i)))))

  (.order buf ByteOrder/BIG_ENDIAN)

  (in-steps-of
   (mk-arr 80 ByteOrder/BIG_ENDIAN #(.putFloat % a-float))
   4 buf
   (fn [i]
     (is (= a-float (.getFloat buf)))
     (is (= a-float (.getFloat buf i))))
   (fn [i]
     (is (= a-float (.getFloatBigEndian buf)))
     (is (= a-float (.getFloatBigEndian buf i))))
   (fn [i]
     (.putFloat buf a-float)
     (is (= a-float (.getFloat buf i)))
     (.putFloat buf i a-float)
     (is (= a-float (.getFloat buf i))))
   (fn [i]
     (.putFloatBigEndian buf a-float)
     (is (= a-float (.getFloat buf i)))
     (.putFloatBigEndian buf i a-float)
     (is (= a-float (.getFloat buf i)))))

  (.order buf ByteOrder/LITTLE_ENDIAN)

  (in-steps-of
   (mk-arr 80 ByteOrder/LITTLE_ENDIAN #(.putFloat % a-float))
   4 buf
   (fn [i]
     (is (= a-float (.getFloat buf)))
     (is (= a-float (.getFloat buf i))))
   (fn [i]
     (is (= a-float (.getFloatLittleEndian buf)))
     (is (= a-float (.getFloatLittleEndian buf i))))
   (fn [i]
     (.putFloat buf a-float)
     (is (= a-float (.getFloat buf i)))
     (.putFloat buf i a-float)
     (is (= a-float (.getFloat buf i))))
   (fn [i]
     (.putFloatLittleEndian buf a-float)
     (is (= a-float (.getFloat buf i)))
     (.putFloatLittleEndian buf i a-float)
     (is (= a-float (.getFloat buf i)))))

  (.order buf ByteOrder/BIG_ENDIAN)

  ;; INT accessors
  (in-steps-of
   (mk-arr 80 ByteOrder/BIG_ENDIAN #(.putInt % a-int))
   4 buf
   (fn [i]
     (is (= a-int (.getInt buf)))
     (is (= a-int (.getInt buf i))))
   (fn [i]
     (is (= a-int (.getIntBigEndian buf)))
     (is (= a-int (.getIntBigEndian buf i))))
   (fn [i]
     (.putInt buf a-int)
     (is (= a-int (.getInt buf i)))
     (.putInt buf i a-int)
     (is (= a-int (.getInt buf i))))
   (fn [i]
     (.putIntBigEndian buf a-int)
     (is (= a-int (.getInt buf i)))
     (.putIntBigEndian buf i a-int)
     (is (= a-int (.getInt buf i)))))

  (.order buf ByteOrder/LITTLE_ENDIAN)

  (in-steps-of
   (mk-arr 80 ByteOrder/LITTLE_ENDIAN #(.putInt % a-int))
   4 buf
   (fn [i]
     (is (= a-int (.getInt buf)))
     (is (= a-int (.getInt buf i))))
   (fn [i]
     (is (= a-int (.getIntLittleEndian buf)))
     (is (= a-int (.getIntLittleEndian buf i))))
   (fn [i]
     (.putInt buf a-int)
     (is (= a-int (.getInt buf i)))
     (.putInt buf i a-int)
     (is (= a-int (.getInt buf i))))
   (fn [i]
     (.putIntLittleEndian buf a-int)
     (is (= a-int (.getInt buf i)))
     (.putIntLittleEndian buf i a-int)
     (is (= a-int (.getInt buf i)))))

  (.order buf ByteOrder/BIG_ENDIAN)

  ;; SHORT accessors
  (in-steps-of
   (mk-arr 80 ByteOrder/BIG_ENDIAN #(.putShort % a-short))
   2 buf
   (fn [i]
     (is (= a-short (.getShort buf)))
     (is (= a-short (.getShort buf i))))
   (fn [i]
     (is (= a-short (.getShortBigEndian buf)))
     (is (= a-short (.getShortBigEndian buf i))))
   (fn [i]
     (.putShort buf a-short)
     (is (= a-short (.getShort buf i)))
     (.putShort buf i a-short)
     (is (= a-short (.getShort buf i))))
   (fn [i]
     (.putShortBigEndian buf a-short)
     (is (= a-short (.getShort buf i)))
     (.putShortBigEndian buf i a-short)
     (is (= a-short (.getShort buf i)))))

  (.order buf ByteOrder/LITTLE_ENDIAN)

  (in-steps-of
   (mk-arr 80 ByteOrder/LITTLE_ENDIAN #(.putShort % a-short))
   2 buf
   (fn [i]
     (is (= a-short (.getShort buf)))
     (is (= a-short (.getShort buf i))))
   (fn [i]
     (is (= a-short (.getShortLittleEndian buf)))
     (is (= a-short (.getShortLittleEndian buf i))))
   (fn [i]
     (.putShort buf a-short)
     (is (= a-short (.getShort buf i)))
     (.putShort buf i a-short)
     (is (= a-short (.getShort buf i))))
   (fn [i]
     (.putShortLittleEndian buf a-short)
     (is (= a-short (.getShort buf i)))
     (.putShortLittleEndian buf i a-short)
     (is (= a-short (.getShort buf i)))))

  (.order buf ByteOrder/BIG_ENDIAN)

  ;; Exceptional cases
  (is (thrown? IndexOutOfBoundsException (.get buf -1)))
  (is (thrown? IndexOutOfBoundsException (.get buf 100)))
  (is (thrown? IllegalArgumentException (.position buf -1)))
  (is (thrown? IllegalArgumentException (.position buf 101)))
  (is (thrown? IllegalArgumentException (.limit buf -1)))
  (is (thrown? IllegalArgumentException (.limit buf 101)))

  (.limit buf 50)
  (is (thrown? IllegalArgumentException (.position buf 51))))

(deftest heap-allocated-buffers
  (test-buffer (Buffer/allocate 100)))

(deftest byte-buffer-backed-buffers
  (test-buffer (Buffer/wrap (ByteBuffer/allocate 100))))

(deftest composite-buffer
  (test-buffer
   (Buffer/wrap
    (Buffer/allocate 20)
    (Buffer/allocate 20)
    (Buffer/allocate 20)
    (Buffer/allocate 20)
    (Buffer/allocate 20))))

(deftest wrapping-non-buffer-object
  (is (thrown? IllegalArgumentException (Buffer/wrap 1))))
