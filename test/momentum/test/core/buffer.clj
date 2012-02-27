(ns momentum.test.core.buffer
  (:use
   clojure.test
   momentum.core)
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
   [momentum.buffer
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

  (.put buf 0 blank)

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

  ;; Slice
  (.put buf 0 increasing)
  (.position buf 5)
  (.limit buf 95)

  (let [sliced (.slice buf)]
    (is (= 0 (.position sliced)))
    (is (= 90 (.limit sliced)))
    (is (= 90 (.capacity sliced)))
    (is (arr= (arr-range increasing 5 90)
              (arr-get sliced 0 90))))

  (.clear buf)
  (.put buf 0 increasing)

  ;; Slice maintains byte order
  (.order buf ByteOrder/BIG_ENDIAN)
  (is (= ByteOrder/BIG_ENDIAN (.order (.slice buf))))

  (.order buf ByteOrder/LITTLE_ENDIAN)
  (is (= ByteOrder/LITTLE_ENDIAN (.order (.slice buf))))

  (.order buf ByteOrder/BIG_ENDIAN)
  (.clear buf)

  ;; equals(other)
  (is (= buf (Buffer/wrap increasing)))
  (is (not= buf (Buffer/wrap decreasing)))

  (.window buf 10 10)
  (is (= buf (Buffer/wrap (arr-range increasing 10 10))))
  (is (not= buf (Buffer/wrap increasing)))

  ;; toByteArray conversions

  (let [actual (.toByteArray buf)]
    (is (arr= increasing actual)))

  ;; toByteBuffer conversion
  (.clear buf)

  (let [bb (.toByteBuffer buf)]
    (is (= 0 (.position bb)))
    (is (= 100 (.limit bb)))
    (is (= 100 (.capacity bb)))
    (is (= bb (ByteBuffer/wrap increasing))))

  (.position buf 10)
  (.limit buf 90)

  (let [bb (.toByteBuffer buf)]
    (is (= 10 (.position bb)))
    (is (= 90 (.limit bb)))
    (is (= 100 (.capacity bb)))
    (is (= bb (ByteBuffer/wrap increasing 10 80))))

  ;; toChannelBuffer conversion

  (.clear buf)

  (let [cb  (.toChannelBuffer buf)
        exp (ChannelBuffers/wrappedBuffer increasing)]
    (is (= 0 (.readerIndex cb)))
    (is (= 100 (.writerIndex cb)))
    (is (= 100 (.capacity cb)))

    (is (= cb exp)))

  (.position buf 10)
  (.limit buf 90)

  (let [cb (.toChannelBuffer buf)]
    (is (= 10 (.readerIndex cb)))
    (is (= 90 (.writerIndex cb)))
    (is (= 100 (.capacity cb)))
    (let [expected (ChannelBuffers/wrappedBuffer increasing)]
      (.setIndex expected 10 90)
      (is (= cb expected))))

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

(deftest wrapped-array-with-range
  (let [arr (byte-array 110 (repeat (byte 79)))]
    (test-buffer (Buffer/wrapArray arr 5 100))

    (let [expected (byte-array 5 (repeat (byte 79)))]
      (is (arr= expected (arr-range arr 0 5)))
      (is (arr= expected (arr-range arr 105 5)))))

  (let [arr (byte-array 105 (repeat (byte 79)))]
    (test-buffer (Buffer/wrapArray arr 0 100))

    (let [expected (byte-array 5 (repeat (byte 79)))]
      (is (arr= expected (arr-range arr 100 5))))))

(deftest direct-allocated-buffers
  (test-buffer (Buffer/allocateDirect 100))
  (let [buf (ByteBuffer/allocateDirect 200)]
    (.position buf 50)
    (.limit buf 150)
    (test-buffer (Buffer/wrap (.slice buf)))))

(deftest byte-buffer-backed-buffers-usage
  (test-buffer (Buffer/wrap (ByteBuffer/allocate 100)))
  (let [buf (ByteBuffer/allocate 200)]
    (.position buf 50)
    (.limit buf 150)
    (test-buffer (Buffer/wrap buf))))

(deftest channel-buffer-backed-buffers-usage
  (let [cb (ChannelBuffers/buffer 100)]
    (.writerIndex cb 100)
    (test-buffer (Buffer/wrap cb)))

  (test-buffer (Buffer/wrap (mk-channel-buffer 100)))

  (let [cb (mk-channel-buffer 200)]
    (.setIndex cb 30 130)
    (test-buffer (Buffer/wrap cb)))

  (let [cb  (mk-channel-buffer 100)
        buf (Buffer/wrap cb)]

    (is (= ByteOrder/BIG_ENDIAN
           (.order buf)
           (.order cb)))

    (.order buf ByteOrder/LITTLE_ENDIAN)

    (is (= ByteOrder/LITTLE_ENDIAN
           (.order (.toChannelBuffer buf))))))

(deftest composite-buffer-usage
  (test-buffer
   (Buffer/wrap
    (Buffer/allocate 20)
    (Buffer/allocate 20)
    (Buffer/allocate 20)
    (Buffer/allocate 20)
    (Buffer/allocate 20))))

(deftest composite-buffer-with-windowed-buffers
  (let [buf1 (Buffer/wrap (Arrays/copyOf increasing 100))
        buf2 (Buffer/wrap (Arrays/copyOf decreasing 100))]
    (test-buffer
     (Buffer/wrap
      (.position buf1 50)
      (.limit buf2 50)))))

(deftest composite-channel-buffer-backed
  (let [buf1 (Buffer/wrap (mk-channel-buffer 100))
        buf2 (Buffer/wrap (mk-channel-buffer 100))]
    (test-buffer
     (Buffer/wrap
      (-> (Buffer/wrap (mk-channel-buffer 100))
          (.limit 50))
      (-> (Buffer/wrap (mk-channel-buffer 100))
          (.position 50))))))

(deftest channel-buffer-backed-subset
  (let [buf (Buffer/wrap (mk-channel-buffer 200))]
    (.position buf 50)
    (.limit buf 150)
    (test-buffer (.slice buf))))

(deftest dynamic-buffer-usage
  (let [buf (Buffer/dynamic 1 100)]
    (is (= 100 (.limit buf)))
    (test-buffer buf)))

(deftest dynamic-buffer-edge-cases
  (let [buf (Buffer/dynamic 0 1024)]
    (is (= 0 (.get buf 0) (.get buf 1023)))
    (.put buf 1000 1)
    (is (= 1 (.get buf 1000)))))

(deftest slicing-dynamic-buffers
  (let [buf (wrap "foo" "bar" "baz")]
    (is (= (buffer "foo") (.slice buf 0 3)))
    (is (= (buffer "bar") (.slice buf 3 3)))
    (is (= (buffer "baz") (.slice buf 6 3)))

    (is (= (buffer "foob") (.slice buf 0 4)))
    (is (= (buffer "foobarb") (.slice buf 0 7)))
    (is (= (buffer "oob") (.slice buf 1 3)))

    (let [buf (wrap increasing decreasing increasing decreasing increasing)
          exp (buffer 500 increasing decreasing increasing decreasing increasing)]
      (doseq [[idx len] {0   200
                         100 300
                         50  350
                         123 300}]
        (is (= (.slice exp idx len)
               (.slice buf idx len))))))

  (let [buf (Buffer/dynamic 0 500)
        exp (buffer 500 blank blank blank blank blank)]
    (is (= (.slice exp 50 400)
           (.slice buf 50 400)))))

(deftest wrapping
  (is (thrown? IllegalArgumentException (Buffer/wrap (Object.)))))

(deftest unsigned-accessors
  (let [buf (buffer 4)]

    ;; ==== BYTES
    (.put buf 0 (byte -1))
    (is (= 255 (.getUnsigned buf 0)))
    (is (= 0 (.position buf)))
    (is (= 255 (.getUnsigned buf)))
    (is (= 1 (.position buf)))

    (.put buf 1 0)
    (.putUnsigned buf 0 234)

    (is (= 0 (.get buf 1)))
    (is (= 234 (.getUnsigned buf 0)))

    (.clear buf)
    (.putUnsigned buf 200)

    (is (= 1 (.position buf)))
    (is (= 0 (.get buf 1)))
    (is (= 200 (.getUnsigned buf 0)))

    ;; ==== INTS
    (.clear buf)
    (.putInt buf 0 -253714183)

    (is (= 4041253113 (.getIntUnsigned buf 0)))
    (is (= 0 (.position buf)))
    (is (= 4041253113 (.getIntUnsigned buf)))
    (is (= 4 (.position buf)))

    (.clear buf)

    (is (= 4041253113 (.getIntUnsignedBigEndian buf 0)))
    (is (= 0 (.position buf)))
    (is (= 4041253113 (.getIntUnsignedBigEndian buf)))
    (is (= 4 (.position buf)))

    (.clear buf)
    (.putIntLittleEndian buf 0 -253714183)

    (is (= 4041253113 (.getIntUnsignedLittleEndian buf 0)))
    (is (= 0 (.position buf)))
    (is (= 4041253113 (.getIntUnsignedLittleEndian buf)))
    (is (= 4 (.position buf)))

    (.clear buf)

    (.putInt buf 0 0)
    (.putIntUnsigned buf 0 404125311)
    (is (= 404125311 (.getIntUnsigned buf 0)))

    (.putInt buf 0 0)
    (.putIntUnsigned buf 404125311)
    (is (= 404125311 (.getIntUnsigned buf 0)))
    (is (= 4 (.position buf)))

    (.clear buf)

    (.putInt buf 0 0)
    (.putIntUnsignedBigEndian buf 0 404125311)
    (is (= 404125311 (.getIntUnsigned buf 0)))

    (.putInt buf 0 0)
    (.putIntUnsignedBigEndian buf 404125311)
    (is (= 404125311 (.getIntUnsigned buf 0)))
    (is (= 4 (.position buf)))

    (.clear buf)

    (.putInt buf 0 0)
    (.putIntUnsignedLittleEndian buf 0 404125311)
    (is (= 404125311 (.getIntUnsignedLittleEndian buf 0)))

    (.putInt buf 0 0)
    (.putIntUnsignedLittleEndian buf 404125311)
    (is (= 404125311 (.getIntUnsignedLittleEndian buf 0)))
    (is (= 4 (.position buf)))


    ;; ==== SHORTS
    (.clear buf)
    (.putInt buf 0 0)
    (.putShort buf 0 -3872)

    (is (= 61664 (.getShortUnsigned buf 0)))
    (is (= 0 (.position buf)))
    (is (= 61664 (.getShortUnsigned buf)))
    (is (= 2 (.position buf)))

    (.clear buf)

    (is (= 61664 (.getShortUnsignedBigEndian buf 0)))
    (is (= 0 (.position buf)))
    (is (= 61664 (.getShortUnsignedBigEndian buf)))
    (is (= 2 (.position buf)))

    (.clear buf)
    (.putShortLittleEndian buf 0 -3872)

    (is (= 61664 (.getShortUnsignedLittleEndian buf 0)))
    (is (= 0 (.position buf)))
    (is (= 61664 (.getShortUnsignedLittleEndian buf)))
    (is (= 2 (.position buf)))

    (.clear buf)

    (.putInt buf 0 0)
    (.putShortUnsigned buf 61664)
    (is (= 61664 (.getShortUnsigned buf 0)))

    (.clear buf)

    (.putInt buf 0 0)
    (.putShortUnsigned buf 61664)
    (is (= 61664 (.getShortUnsigned buf 0)))
    (is (= 2 (.position buf)))

    (.clear buf)

    (.putInt buf 0 0)
    (.putShortUnsignedBigEndian buf 0 61664)
    (is (= 61664 (.getShortUnsigned buf 0)))

    (.putInt buf 0 0)
    (.putShortUnsignedBigEndian buf 61664)
    (is (= 61664 (.getShortUnsigned buf 0)))
    (is (= 2 (.position buf)))

    (.clear buf)

    (.putInt buf 0 0)
    (.putShortUnsignedLittleEndian buf 0 61664)
    (is (= 61664 (.getShortUnsignedLittleEndian buf 0)))

    (.putInt buf 0 0)
    (.putShortUnsignedLittleEndian buf 61664)
    (is (= 61664 (.getShortUnsignedLittleEndian buf 0)))
    (is (= 2 (.position buf)))))

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

;; (deftest wrapping-buffers-with-one-frozen
;;   (is (frozen (wrap (freeze (buffer 10)) (buffer 10))))
;;   (is (frozen (wrap (buffer 10) (freeze (buffer 10))))))

;; (deftest duplicating-frozen-buffer-stays-frozen
;;   (is (frozen (-> (buffer 10) freeze duplicate)))
;;   (is (frozen (-> (mk-channel-buffer 10) buffer freeze duplicate)))
;;   (is (frozen (-> (direct-buffer 10) freeze duplicate)))
;;   (is (frozen (-> (wrap (buffer 10) (buffer 10)) freeze duplicate))))

;; (deftest throws-when-writing-to-frozen-buffer
;;   (is (thrown?
;;        ReadOnlyBufferException
;;        (.put (freeze (buffer 10)) (byte 1)))))

;;
;; === Clojure interface ===
;;

(deftest wrapping-single-buffers
  (is (= (Buffer/wrap "Hello") (buffer "Hello")))

  (is (= (buffer :byte 1 2 3 (buffer "Hello"))
         (doto (buffer 8)
           (.put (byte 1))
           (.put (byte 2))
           (.put (byte 3))
           (.put (buffer "Hello"))
           flip)))

  (is (= (buffer :ubyte 100 200 250 (buffer "H"))
         (doto (buffer 4)
           (.putUnsigned 100)
           (.putUnsigned 200)
           (.putUnsigned 250)
           (.put (buffer "H"))
           flip)))

  (is (= (buffer :short 100 200 250 (buffer "H"))
         (doto (buffer 7)
           (.putShort 100)
           (.putShort 200)
           (.putShort 250)
           (.put (buffer "H"))
           (flip))))

  (is (= (buffer :ushort 1234 2345 40000 (buffer "H"))
         (doto (buffer 7)
           (.putShortUnsigned 1234)
           (.putShortUnsigned 2345)
           (.putShortUnsigned 40000)
           (.put (buffer "H"))
           (flip))))

  (is (= (buffer :byte 1 :int 2 (buffer "H"))
         (doto (buffer 6)
           (.put (byte 1))
           (.putInt 2)
           (.put (buffer "H"))
           (flip)))))

(deftest wrapping-primitives
  (is (= (wrap 1)
         (doto (Buffer/allocate 4) (.putInt 0 1))))
  (is (= (wrap 1 2)
         (doto (Buffer/allocate 8)
           (.putInt 0 1)
           (.putInt 4 2)))))

(deftest seqing-buffer-returns-list-containing-buffer
  (let [b (buffer "Hello")]
    (is (= (list b) (seq b)))))

;; ==== to-string

(deftest invoking-to-string
  (are [a b] (= a (to-string b))
       "" nil
       "" ""))

;; ==== transient
(deftest tracking-transience
  (are [a] (not (transient? a))
       (buffer "Hello")
       (direct-buffer 10)
       (Buffer/wrap (buffer 10) (buffer 10))
       (slice
        (transient! (Buffer/wrap (buffer 10) (buffer 10)))
        5 10))

  (are [a] (transient? a)
       (transient! (buffer "Hello"))
       (slice (transient! (buffer "Hello")) 1 3)
       (transient! (direct-buffer 10))
       (transient! (Buffer/wrap (buffer 10) (buffer 10)))
       (slice
        (transient! (Buffer/wrap (buffer 300) (buffer 300)))
        10 300)))

;; === Regression tests
(deftest composite-buffer-regression-test-1
  (let [b (Buffer/wrap
           (Buffer/allocate 5)
           (Buffer/allocate 5)
           (Buffer/allocate 5))]

    ;; Bugs caused the last get in each line to throw an out of bounds
    ;; exception
    (is (= 0 (.get b 0) (.get b 10)))
    (is (= 0 (.get b 10) (.get b 0)))))
