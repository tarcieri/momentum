(ns picard.test.core.buffer
  (:use
   clojure.test
   picard.core.buffer)
  (:import
   [java.nio
    BufferOverflowException
    BufferUnderflowException
    ReadOnlyBufferException
    ByteBuffer
    ByteOrder]
   [java.util
    Arrays]
   [org.jboss.netty.buffer
    ChannelBuffer
    ChannelBuffers]
   [picard.core
    Buffer]))

;; ==== HELPERS

(def blank (byte-array 100))

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

(defn- mk-channel-buffer
  [size]
  (ChannelBuffers/wrappedBuffer
   (into-array
    [(ByteBuffer/allocate 1)
     (ByteBuffer/allocate 1)
     (ByteBuffer/allocate (- size 2))])))

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

(defn- arr-get
  ([buf idx len pad]
     (Arrays/copyOf (arr-get buf idx len) (+ len pad)))
  ([buf idx len]
     (let [arr (byte-array len)]
       (.get buf idx arr)
       arr)))

(defn- arr=
  [a b]
  (Arrays/equals a b))

(defn- arr-range
  ([a from len pad]
     (Arrays/copyOf (arr-range a from len) (+ len pad)))
  ([a from len]
     (Arrays/copyOfRange a from (+ from len))))

(defn- in-steps-of
    [base step buf & fns]
    (dotimes [i step]
      (.put buf i base)

      (doseq [f fns]
        (.position buf i)

        (dotimes [j (/ 80 step)]
          (f (+ i (* step j)))))))

;; ==== COMMON TESTS

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
  (is (arr= (arr-get buf 0 100) d-at-50))

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

  (is (arr= (arr-get buf 0 100) increasing))

  (.flip buf)

  (is (= 0 (.position buf)))
  (is (= 100 (.limit buf)))

  (.put buf 0 decreasing 0 100)
  (dotimes [i 100]
    (is (= (.position buf i)))
    (is (= (- 100 i) (.get buf))))

  ;; Relative multibyte get / put

  ;; Getting big endian chars
  (in-steps-of
   (mk-arr 80 ByteOrder/BIG_ENDIAN #(.putChar % (char 1234)))
   2 buf
   (fn [i]
     (is (= (char 1234) (.getChar buf)))
     (is (= (char 1234) (.getChar buf i))))
   (fn [i]
     (is (= (char 1234) (.getCharBigEndian buf)))
     (is (= (char 1234) (.getCharBigEndian buf i)))))

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
     (is (= (char 1234) (.getChar buf i)))))

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
     (is (= a-long (.getLong buf i)))))

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
     (is (= a-long (.getLong buf i)))))

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

(defn- test-transfers
  [a b]
  (let [reset
        (fn []
          (.clear a)
          (.clear b)
          (.put a 0 blank)
          (.put b 0 blank))]

    (reset)

    (.put a 0 increasing)
    (.get a b)

    (is (= 100 (.position a) (.position b)))
    (is (= 100 (.limit a) (.limit b)))

    (is (arr= increasing (arr-get b 0 100)))

    (reset)

    (.put a 0 increasing)
    (.put b a)

    (is (= 100 (.position a) (.position b)))
    (is (= 100 (.limit a) (.limit b)))

    (is (arr= increasing (arr-get b 0 100)))

    (reset)

    (.put a 0 increasing)

    (.window a 20 10)
    (.window b 45 10)

    (.get a b)

    (is (= 30 (.position a)))
    (is (= 55 (.position b)))

    (is (arr= (arr-get b 0 45) (arr-range blank 0 45)))
    (is (arr= (arr-get b 45 55) (arr-range increasing 20 10 45)))

    (reset)

    (.put a 0 increasing)

    (.window a 20 10)
    (.window b 45 10)

    (.put b a)

    (is (= 30 (.position a)))
    (is (= 55 (.position b)))

    (is (arr= (arr-get b 0 45) (arr-range blank 0 45)))
    (is (arr= (arr-get b 45 55) (arr-range increasing 20 10 45)))

    (reset)

    (.put a 0 increasing)
    (.get a b 0)

    (is (= 100 (.position a)))
    (is (= 0 (.position b)))

    (is (arr= increasing (arr-get b 0 100)))

    (reset)

    (.put a 0 increasing)
    (.put b a 0)

    (is (= 0 (.position a)))
    (is (= 100 (.position b)))

    (is (arr= increasing (arr-get b 0 100)))

    (reset)

    (.put a 0 increasing)
    (.limit a 95)
    (.get a b 5)

    (is (= 95 (.position a)))
    (is (= 0 (.position b)))

    (is (arr= (arr-get b 5 90 10)
              (arr-range increasing 0 90 10)))

    (reset)

    (.put a 0 increasing)
    (.limit b 90)
    (.put b a 5)

    (is (= 90 (.position b)))
    (is (= 0 (.position a)))

    (is (arr= (arr-get b 0 90 10)
              (arr-range increasing 5 90 10)))

    (reset)

    (.put a 0 increasing)
    (.get a 5 b 6 90)

    (is (= 0 (.position a) (.position b)))

    (is (arr= (arr-get b 6 90)
              (arr-range increasing 5 90)))

    (reset)

    (.put a 0 increasing)
    (.put b 5 a 6 90)

    (is (= 0 (.position a) (.position b)))

    ;; Start is blank
    (is (arr= (arr-get b 0 5) (arr-range blank 0 5)))
    ;; End is blank
    (is (arr= (arr-get b 95 5) (arr-range blank 0 5)))
    ;; Middle is filled
    (is (arr= (arr-get b 5 90 10)
              (arr-range increasing 6 90 10)))

    (reset)

    (is (thrown?
         BufferUnderflowException
         (.get a 90 b 0 20)))

    (is (thrown?
         BufferOverflowException
         (.get a 0 b 90 20)))

    (.window a 0 100)
    (.window b 0 40)
    (is (thrown?
         BufferOverflowException
         (.get a b)))))

;; TODO:
;; * get(byte[])
;; * put(byte[])

(deftest heap-allocated-buffers
  (test-buffer (Buffer/allocate 100)))

(deftest converting-heap-allocated-buffers
  (let [buf (Buffer/allocate 100)]
    (.put buf d-at-50)
    (.flip buf)

    (is (= (.toByteBuffer buf)
           (ByteBuffer/wrap d-at-50)))

    (is (= (.toChannelBuffer buf)
           (ChannelBuffers/wrappedBuffer d-at-50)))

    (is (arr= (.toByteArray buf) d-at-50)))

  (let [buf (Buffer/wrapArray d-at-50 5 95)]
    (is (= (.toByteBuffer buf)
           (ByteBuffer/wrap d-at-50 5 95)))

    (is (= (.toChannelBuffer buf)
           (ChannelBuffers/wrappedBuffer d-at-50 5 95)))

    (is (arr= (.toByteArray buf)
                       (Arrays/copyOfRange d-at-50 5 95))))

  (let [buf (Buffer/wrapArray d-at-50 0 95)]
    (is (= (.toByteBuffer buf)
           (ByteBuffer/wrap d-at-50 0 95)))

    (is (= (.toChannelBuffer buf)
           (ChannelBuffers/wrappedBuffer d-at-50 0 95)))

    (is (arr= (.toByteArray buf)
                       (Arrays/copyOf d-at-50 95)))))

(deftest direct-allocated-buffers
  (test-buffer (Buffer/allocateDirect 100)))

(deftest converting-byte-buffer-backed-buffers
  (let [buf (Buffer/allocateDirect 100)]
    (.put buf d-at-50)
    (.flip buf)

    (is (= (.toByteBuffer buf)
           (ByteBuffer/wrap d-at-50)))

    (is (= (.toChannelBuffer buf)
           (ChannelBuffers/wrappedBuffer d-at-50)))

    (is (arr= (.toByteArray buf) d-at-50))))

(deftest wrapped-arry-with-offset-usage
  (let [arr (byte-array 102)]
    (aset-byte arr 0 79)
    (aset-byte arr 101 81)
    (test-buffer (Buffer/wrapArray arr 1 100))
    (is (= 79 (aget arr 0)))
    (is (= 81 (aget arr 101)))))

(deftest byte-buffer-backed-buffers-usage
  (test-buffer (Buffer/wrap (ByteBuffer/allocate 100))))

(deftest channel-buffer-backed-buffers-usage
  (test-buffer (Buffer/wrap (ChannelBuffers/buffer 100))))

(deftest composite-buffer-usage
  (test-buffer
   (Buffer/wrap
    (Buffer/allocate 20)
    (Buffer/allocate 20)
    (Buffer/allocate 20)
    (Buffer/allocate 20)
    (Buffer/allocate 20))))

(deftest dynamic-buffer-usage
  (let [buf (Buffer/dynamic 1 100)]
    (is (= 1 (.limit buf)))
    (.limit buf 100)
    (test-buffer buf)))

(deftest wrapping-non-buffer-object
  (is (thrown? IllegalArgumentException (Buffer/wrap 1))))


;;
;; === Transfers ===
;;

(deftest heap-buffer-transfers
  (test-transfers
   (Buffer/allocate 100)
   (Buffer/allocate 100))

  (test-transfers
   (Buffer/allocate 100)
   (Buffer/allocateDirect 100))

  (test-transfers
   (Buffer/allocate 100)
   (Buffer/wrap
    (mk-channel-buffer 100)))

  (test-transfers
   (Buffer/allocate 100)
   (Buffer/wrap
    (Buffer/allocate 20)
    (Buffer/allocate 50)
    (Buffer/allocate 30))))

(deftest byte-buffer-transfers
  (test-transfers
   (Buffer/allocateDirect 100)
   (Buffer/allocateDirect 100))

  (test-transfers
   (Buffer/allocateDirect 100)
   (Buffer/allocate 100))

  (test-transfers
   (Buffer/allocateDirect 100)
   (Buffer/wrap
    (mk-channel-buffer 100)))

  (test-transfers
   (Buffer/allocateDirect 100)
   (Buffer/wrap
    (Buffer/allocate 20)
    (Buffer/allocateDirect 30)
    (Buffer/wrap
     (Buffer/allocate 20)
     (Buffer/allocate 30)))))

(deftest composite-transfers
  (test-transfers
   (Buffer/wrap
    (Buffer/allocate 30)
    (Buffer/allocate 30)
    (Buffer/allocate 40))
   (Buffer/wrap
    (Buffer/allocate 40)
    (Buffer/allocate 20)
    (Buffer/allocate 40)))

  (test-transfers
   (Buffer/wrap
    (Buffer/allocate 30)
    (Buffer/allocate 30)
    (Buffer/allocate 40))
   (Buffer/allocate 100))

  (test-transfers
   (Buffer/wrap
    (Buffer/allocate 30)
    (Buffer/allocate 30)
    (Buffer/allocate 40))
   (Buffer/allocateDirect 100))

  (test-transfers
   (Buffer/wrap
    (Buffer/allocate 30)
    (Buffer/allocate 30)
    (Buffer/allocate 40))
   (Buffer/wrap
    (mk-channel-buffer 100))))

;;
;; === Other stuff ===
;;

(deftest wrapping-buffers-with-one-frozen
  (is (frozen (wrap (freeze (buffer 10)) (buffer 10))))
  (is (frozen (wrap (buffer 10) (freeze (buffer 10))))))

(deftest duplicating-frozen-buffer-stays-frozen
  (is (frozen (-> (buffer 10) freeze duplicate)))
  (is (frozen (-> (mk-channel-buffer 10) buffer freeze duplicate)))
  (is (frozen (-> (direct-buffer 10) freeze duplicate)))
  (is (frozen (-> (wrap (buffer 10) (buffer 10)) freeze duplicate))))

(deftest throws-when-writing-to-frozen-buffer
  (is (thrown?
       ReadOnlyBufferException
       (.put (freeze (buffer 10)) (byte 1)))))
