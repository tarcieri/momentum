(ns momentum.test.core.async
  (:use
   clojure.test
   momentum.core))

(defn- deferred-inc
  [i]
  (let [d (deferred)]
    (future
      (Thread/sleep 10)
      (put d (inc i)))
    d))

(deftest simple-do-async
  (let [res (atom nil)]
    (receive
     (doasync 1
       inc inc)
     #(reset! res %)
     identity)
    (is (= 3 @res))))

(deftest successful-pipeline-seeded-with-deferred-value
  (let [res  (atom nil)
        dval (deferred)]
    (receive
     (doasync dval
       inc inc)
     #(compare-and-set! res nil %)
     #(reset! res %))
    (put dval 1)
    (is (= 3 @res))))

(deftest successful-pipeline-with-deferred-values-at-each-stage
  (is (= 3 @(doasync 1 deferred-inc deferred-inc))))

(deftest aborting-seed-deferred-value-aborts-pipeline
  (let [res (atom nil)
        err (Exception.)
        val (deferred)]
    (receive
     (doasync val inc inc)
     #(reset! res %)
     #(compare-and-set! res nil %))
    (abort val err)
    (is (= err @res))))

(deftest thrown-exception-in-stage-aborts-pipeline
  (let [res (atom nil)
        err (Exception.)]
    (receive
     (doasync 1 (fn [_] (throw err)) inc)
     #(reset! res %)
     #(compare-and-set! res nil %))
    (is (= err @res))

    (reset! res nil)

    (receive
     (doasync 1 inc (fn [_] (throw err)))
     #(reset! res %)
     #(compare-and-set! res nil %))
    (is (= err @res))))

;; ==== Catching exceptions

(deftest successful-pipeline-with-catch-statement
  (let [res (atom nil)]
    (receive
     (doasync 1 inc
       (catch Exception e
         (reset! res e)))
     #(compare-and-set! res nil %)
     #(reset! res %))

    (is (= 2 @res))))

(deftest successful-blocking-pipeline-with-catch
  (let [d (deferred)]
    (future
      (Thread/sleep 10)
      (put d 1))
    (is (= 2
           @(doasync d inc
              (catch Exception e :fail))))))

(deftest catching-aborted-pipeline-succeeds-with-value
  (let [res  (atom nil)
        dval (deferred)]
    (receive
     (doasync dval
       identity
       (catch Exception _ :hello))
     #(compare-and-set! res nil %)
     #(reset! dval %))

    (abort dval (Exception.))
    (is (= :hello @res))))

(deftest catching-exception-thrown-during-stage-succeeds-with-value
  (let [res (atom nil)]
    (receive
     (doasync 1
       (fn [_] (throw (Exception.)))
       (catch Exception e :hello))
     #(reset! res %)
     #(reset! res %))
    (is (= :hello @res))))

;; === Finally statements

(deftest finally-statement-called-after-value-realized
  (let [res (atom nil)]
    (doasync 1
      #(reset! res %)
      (finally
       (compare-and-set! res 1 :win)))
    (is (= :win @res))))

(deftest finally-statement-called-after-aborted
  (let [res (atom nil)]
    (doasync 1
      (fn [_] (throw (Exception.)))
      (finally
       (compare-and-set! res nil :win)))
    (is (= :win @res))))

(deftest finally-statement-called-after-caught-exception
  (let [res (atom nil)]
    (doasync 1
      (fn [_] (throw (Exception.)))
      (catch Exception _)
      (finally
       (compare-and-set! res nil :win)))
    (is (= :win @res))))

(deftest throwing-in-finally
  (is (thrown-with-msg?
        Exception #"BOOM"
        @(doasync 1 inc
           (finally (throw (Exception. "BOOM")))))))

(deftest throwing-in-finally-overrides-aborted-exception
  (is (thrown-with-msg?
        Exception #"BOOM"
        @(doasync 1
           (fn [_] (throw (Exception. "BAM")))
           (finally (throw (Exception. "BOOM")))))))

(deftest aborting-in-progress
  (is (thrown-with-msg?
        Exception #"BOOM"
        (let [d (deferred)]
          @(doasync 1 inc
             (finally (throw (Exception. "BOOM"))))
          (abort d (Exception. "BAM"))))))

;; ==== Aborting pipelines in progress

(deftest aborting-a-pipeline-after-first-stage
  (let [res (atom nil)
        err (Exception. "BOOM")
        pipeline
        (doasync 1 inc
          (fn [v]
            (compare-and-set! res nil v)
            (future*
             (Thread/sleep 10)
             (inc v))))]
    (abort pipeline err)
    (receive
     pipeline
     (fn [_] (reset! res :fail))
     #(compare-and-set! res 2 %))
    (is (= err @res))))

(deftest aborting-a-pipeline-before-seed-is-realized
  (let [res (atom nil) res2 (atom nil)
        err (Exception. "BOOM")
        pipeline
        (doasync (future* (Thread/sleep 20) 1)
          #(reset! res2 %))]
    (abort pipeline err)
    (receive
     pipeline
     (fn [v] (reset! res [v :fail]))
     #(compare-and-set! res nil %))
    (Thread/sleep 40)
    (is (= err @res))
    (is (nil? @res2))))

(deftest aborting-a-pipeline-mid-stage
  (let [res (atom nil) res2 (atom nil)
        err (Exception. "BOOM")
        pipeline
        (doasync 1
          (fn [v]
            (future*
             (Thread/sleep 10)
             (inc v)))
          #(reset! res2 %))]
    (abort pipeline err)
    (receive
     pipeline
     (fn [v] (reset! res [v :fail]))
     #(compare-and-set! res nil %))
    (Thread/sleep 40)
    (is (= err @res))
    (is (nil? @res2))))

;; ==== recur*

(deftest simple-async-recursion-with-primitives
  (let [res (atom nil)]
    (receive
     (doasync 1
       (fn [val]
         (if (< val 4)
           (recur* (inc val))
           (inc val))))
     #(compare-and-set! res nil %)
     #(reset! res %))
    (is (= 5 @res))))

(deftest simple-async-recursion-with-deferred-values
  (let [val (deferred)]
    (future
      (Thread/sleep 10)
      (put val 1))
    (is (= 5 @(doasync val
                (fn [val]
                  (if (< val 4)
                    (let [nxt (deferred)]
                      (future
                        (Thread/sleep 10)
                        (put nxt (inc val)))
                      (recur* nxt))
                    (inc val))))))))

;; ==== async-seq

(deftest async-seq-basics
  (are [x] (seq? x)
       (async-seq nil)
       (async-seq [])
       (async-seq [1 2]))

  (are [x y] (= x y)
       (async-seq nil)   ()
       (async-seq [nil]) [nil]))

(deftest async-seq-is-just-like-lazy-seq-by-default
  (let [observed? (atom nil)
        my-seq (async-seq (compare-and-set! observed? nil :yep) [:hello])]
    (is (nil? @observed?))
    (is (= (list :hello) (seq my-seq)))
    (is (= :yep @observed?))))

(deftest async-seq-with-simple-synchronous-pipeline
  (are [x y] (= x y)
       (async-seq (doasync nil))   ()
       (async-seq (doasync [nil])) [nil]))

(defn- count-seq [count]
  (async-seq
    (future*
     (Thread/sleep 5)
     (when (> count 0)
       (cons count (count-seq (dec count)))))))

(deftest async-seq-with-real-async-stuff-happening
  (let [res (atom [])]
    @(doasync (count-seq 3)
       (fn [[v & more]]
         (when v
           (swap! res #(conj % v))
           (recur* more))))

    ;; Check the result
    (is (= [3 2 1] @res))))

(deftest async-seq-catches-thrown-exceptions
  (let [res (atom nil)
        err (Exception. "BOOM")
        seq (async-seq (throw err))]
    (doasync seq
      (catch Exception e (reset! res e)))
    (is (= err @res))))

(deftest async-seq-catches-async-exceptions
  (let [seq
        (async-seq
          (future*
           (Thread/sleep 10)
           (throw (Exception. "BOOM"))))]
    (is (thrown-with-msg?
          Exception #"BOOM"
          @(doasync seq)))))

;; ==== doseq*

(deftest simple-synchronous-doseq
  (let [res (atom [])]
    (doseq* [val [:hello :goodbye :later]]
      (swap! res #(conj % val)))
    (is (= [:hello :goodbye :later] @res))))

(deftest simple-asynchronous-doseq
  (let [ch (channel) res (atom [])]
    (doseq* [val (seq ch)]
      (swap! res #(conj % val)))
    (put ch :hello)
    (put ch :goodbye)
    (put-last ch :later)
    (is (= [:hello :goodbye :later] @res))))

;; ==== map*

