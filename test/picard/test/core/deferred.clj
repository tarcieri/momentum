(ns picard.test.core.deferred
  (:use
   clojure.test
   picard.core.deferred))

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

;; ==== Deferred values

(deftest successfully-realizing-a-deferred-value
  (let [dval (deferred)
        res  (atom nil)]
    (is (= dval (receive dval
                         #(compare-and-set! res nil %)
                         (fn [_] (reset! res :fail)))))
    (is (nil? @res))
    (is (= dval (put dval :hello)))
    (is (= :hello @res)))

  (let [dval (deferred)
        res  (atom nil)]

    (is (= dval (put dval :hello)))
    (receive
     dval
     #(compare-and-set! res nil %)
     (fn [_] (reset! res :fail)))
    (is (= :hello @res))))

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

(deftest observing-an-element-materializes-the-deferred-value
  (let [ch (channel)
        dv (enqueue ch :hello)]
    (is (not (realized? dv)))
    (receive (seq ch) identity identity)
    (is (not (realized? dv)))
    (receive (seq ch) (fn [[first]]) identity)
    (is (realized? dv))))

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
    (is (= [:hello nil] @res)))

  (let [ch  (channel)
        res (atom nil)]
    (put-last ch :hello)
    (receive
     (seq ch)
     (fn [[val more]] (reset! res [val more]))
     identity)
    (is (= [:hello nil] @res))))

(deftest closing-channel-after-observing-deferred-seq-tail
  (let [ch  (channel)
        res (atom nil)]
    (receive
     (seq ch)
     (fn [[val & more]]
       (compare-and-set! res nil val)
       (receive more #(compare-and-set! res :hello %) identity))
     identity)

    (put ch :hello)
    (is (= :hello @res))
    (close ch)
    (is (= [nil] @res))))

;; Test calling next / rest w/o ever calling first
