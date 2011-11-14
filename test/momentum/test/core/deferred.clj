(ns momentum.test.core.deferred
  (:use
   clojure.test
   momentum.core))

;; ==== Regular objects

(deftest registering-callback-on-object
  (let [dval1 :hello
        res   (atom nil)]

    (is (= dval1
           (receive dval1
                    #(compare-and-set! res nil %)
                    (fn [_] (reset! res :fail)))))
    (is (= :hello @res))

    (is (nil? (receive nil
                       #(compare-and-set! res :hello %)
                       (fn [_] (reset! res :fail)))))
    (is (nil? @res))))

;; ==== Async values

(deftest successfully-realizing-a-deferred-value
  (let [dval (deferred)
        res  (atom nil)]
    (is (= dval (receive dval
                         #(compare-and-set! res nil %)
                         (fn [_] (reset! res :fail)))))
    (is (nil? @res))
    (is (= true (put dval :hello)))
    (is (= :hello @res)))

  (let [dval (deferred)
        res  (atom nil)]

    (is (= true (put dval :hello)))
    (receive
     dval
     #(compare-and-set! res nil %)
     (fn [_] (reset! res :fail)))
    (is (= :hello @res))))

(deftest realizing-deferred-value-by-invoking-it
  (let [dval (deferred)
        res  (atom nil)]
    (dval :hello)
    (is (= :hello @dval))))

(deftest aborting-deferred-values
  (let [dval (deferred)
        err  (Exception.)
        res  (atom nil)]
    (receive dval
             (fn [_] (reset! res :fail))
             #(compare-and-set! res nil %))
    (abort dval err)
    (is (= err @res)))

  (let [dval (deferred)
        err  (Exception.)
        res  (atom nil)]
    (abort dval err)
    (receive dval
             (fn [_] (reset! res :fail))
             #(compare-and-set! res nil %))
    (is (= err @res))))

(deftest registering-multiple-receive-callbacks
  (let [dval (deferred)
        res  (atom [])]
    (dotimes [_ 2]
      (receive dval (fn [v] (swap! res #(conj % v))) identity))
    (put dval :hello)
    (is (= @res [:hello :hello]))))

(deftest registering-multiple-receive-callbacks-after-completion
  (let [dval (deferred)
        res  (atom nil)]
    (receive dval identity identity)
    (put dval :hello)
    (receive dval #(reset! res %) identity)
    (is (= @res :hello))))

(deftest dereferencing-realized-deferred-value
  (let [dval (deferred)]
    (put dval :hello)
    (is (= :hello @dval))))

(deftest dereferencing-pending-deferred-values
  (let [dval (deferred)]
    (future
      (Thread/sleep 50)
      (put dval :hello))
    (is (= :hello @dval @dval)))

  (let [dval (deferred)]
    (future
      (Thread/sleep 50)
      (put dval :hello))
    (receive dval identity identity)
    (is (= :hello @dval @dval))))

(deftest dereferencing-aborted-deferred-value
  (let [dval (deferred)]
    (abort dval (Exception. "BOOM"))
    (is (thrown-with-msg? Exception #"BOOM" @dval))
    (is (thrown-with-msg? Exception #"BOOM" @dval))))

(deftest dereferencing-pending-deferred-values-that-get-aborted
  (let [dval (deferred)]
    (future
      (Thread/sleep 50)
      (abort dval (Exception. "BOOM")))
    (is (thrown-with-msg? Exception #"BOOM" @dval))
    (is (thrown-with-msg? Exception #"BOOM" @dval)))

  (let [dval (deferred)]
    (future
      (Thread/sleep 50)
      (abort dval (Exception. "BOOM")))
    (receive dval identity identity)
    (is (thrown-with-msg? Exception #"BOOM" @dval))
    (is (thrown-with-msg? Exception #"BOOM" @dval))))

(deftest putting-into-realized-deferred-value-returns-false
  (let [dval (deferred)]
    (is (= true  (put dval :hello)))
    (is (= false (put dval :fail)))
    (is (= :hello @dval)))

  (let [dval (deferred)]
    (is (= true  (abort dval (Exception. "BOOM"))))
    (is (= false (put dval :fail)))
    (is (thrown-with-msg? Exception #"BOOM" @dval)))

  (let [dval (deferred)]
    (put dval :hello)
    (is (= false (abort dval (Exception. "BOOM"))))
    (is (= :hello @dval)))

  (let [dval (deferred)]
    (abort dval (Exception. "BOOM"))
    (is (= false (abort dval (Exception. "BAM"))))
    (is (thrown-with-msg? Exception #"BOOM" @dval))))

;; ==== Channels

(deftest calling-seq-before-put-and-reading-first-el
  (let [ch (channel)
        sq (seq ch)]
    (put ch :hello)
    (let [[head & tail] sq]
      (is (= :hello head))
      (is (not (realized? tail))))))

(deftest calling-seq-after-put-and-reading-first-el
  (let [ch (channel)]
    (put ch :hello)
    (let [[head & tail] (seq ch)]
      (is (= :hello head))
      (is (not (realized? tail))))))

(deftest registering-callbacks-on-deferred-seq
  (let [ch  (channel)
        res (atom nil)]

    (receive
     (seq ch)
     (fn [[val & more]]
       (reset! res val)
       (receive more #(reset! res (first %)) identity))
     identity)

    (is (nil? @res))
    (put ch :hello)
    (is (= :hello @res))
    (put ch :goodbye)
    (is (= :goodbye @res))))

(deftest aborting-channels
  (let [ch  (channel)
        err (Exception.)
        res (atom nil)]
    (abort ch err)
    (receive (seq ch)
             (fn [_] (reset! res :fail))
             #(compare-and-set! res nil %)))

  (let [ch  (channel)
        err (Exception.)
        res (atom nil)]
    (receive
     (seq ch)
     (fn [[val & more]]
       (reset! res val)
       (receive
        more
        (fn [_] (reset! res :fail))
        (fn [e] (compare-and-set! res :hello e))))
     identity)

    (is (nil? @res))
    (put ch :hello)
    (is (= :hello @res))
    (abort ch err)
    (is (= err @res))
    ;; (is (nil? (seq ch)))
    ))

(deftest queuing-up-elements
  (let [ch  (channel)
        res (atom [])]
    (put ch :hello)
    (put ch :goodbye)

    (receive
     (seq ch)
     (fn [[val & more]]
       (swap! res #(conj % val))
       (receive more (fn [[val]] (swap! res #(conj % val))) identity))
     identity)

    (is (= [:hello :goodbye] @res))))

(deftest closing-channels
  (let [ch  (channel)
        res (atom nil)]
    (put ch :hello)
    (close ch)
    (receive
     (seq ch)
     (fn [[val more]] (reset! res [val more]))
     identity)
    (is (= [:hello nil] @res))))

(deftest observing-an-unrealized-non-blocking-deferred-seq
  (let [ch (channel)]
    (is (thrown? Exception (first (seq ch))))
    (is (thrown? Exception (next (seq ch))))))

(deftest putting-value-into-closed-channel
  (let [ch (channel)]
    (close ch)
    (is (not (put ch :hello)))))

;; TODO:
;; * Add tests for AsyncSeq List interface implementation
