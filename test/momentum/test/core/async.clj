(ns momentum.test.core.async
  (:use
   clojure.test
   momentum.core)
  (:import
   [momentum.async
    AsyncTransferQueue
    TimeoutException]))

;; TODO:
;; * async-seq fn throws, what happens?

(defn- async-inc
  [i]
  (let [val (async-val)]
    (future
      (Thread/sleep 10)
      (put val (inc i)))
    val))

(defn- async-dec-seq [i]
  (async-seq
    (future*
     (Thread/sleep 5)
     (when (> i 0)
       (cons i (async-dec-seq (dec i)))))))

(defn- defer
  [v]
  (future*
   (Thread/sleep 10)
   v))

(deftest doasync-with-no-stages
  (are [x y] (= x y)
       1   @(doasync 1)
       [1] @(doasync [1])
       nil @(doasync nil)
       1   @(doasync (future* (Thread/sleep 10) 1))
       [1] @(doasync (future* (Thread/sleep 10) [1]))
       nil @(doasync (future* (Thread/sleep 10) nil))))

(deftest simple-do-async
  (let [res (atom nil)]
    (receive
     (doasync 1
       inc inc)
     #(reset! res %)
     identity)
    (is (= 3 @res))))

(deftest successful-pipeline-seeded-with-async-value
  (let [res (atom nil)
        val (async-val)]
    (receive
     (doasync val
       inc inc)
     #(compare-and-set! res nil %)
     #(reset! res %))
    (put val 1)
    (is (= 3 @res))))

(deftest successful-pipeline-with-async-values-at-each-stage
  (is (= 3 @(doasync 1 async-inc async-inc))))

(deftest aborting-seed-async-value-aborts-pipeline
  (let [res (atom nil)
        err (Exception.)
        val (async-val)]
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
  (let [val (async-val)]
    (future
      (Thread/sleep 10)
      (put val 1))
    (is (= 2
           @(doasync val inc
              (catch Exception e :fail))))))

(deftest catching-aborted-pipeline-succeeds-with-value
  (let [res (atom nil)
        val (async-val)]
    (receive
     (doasync val
       identity
       (catch Exception _ :hello))
     #(compare-and-set! res nil %)
     #(reset! val %))

    (abort val (Exception.))
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
        (let [val (async-val)]
          @(doasync 1 inc
             (finally (throw (Exception. "BOOM"))))
          (abort val (Exception. "BAM"))))))

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

(deftest simple-async-recursion-with-async-values
  (let [val (async-val)]
    (future
      (Thread/sleep 10)
      (put val 1))
    (is (= 5 @(doasync val
                (fn [val]
                  (if (< val 4)
                    (let [nxt (async-val)]
                      (future
                        (Thread/sleep 10)
                        (put nxt (inc val)))
                      (recur* nxt))
                    (inc val))))))))

(deftest async-recursion-is-tail-recursive-for-sync-values
  (is (= :done
         @(doasync (repeat 100 "a")
            (fn [[v & more]]
              (if more
                (recur* more)
                :done))))))

;; ==== join

(deftest synchronous-joins
  (is (= [1 2 3]
         @(doasync (join 1 2 3)
            (fn [& args] args))))

  (is (= [1]
         @(doasync (join 1)
            (fn [& args] args))))

  (is (= [1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20]
         @(doasync (join 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20)
            (fn [& args] args)))))

(deftest async-joins
  (is (= [1 2 3]
         @(doasync (join (defer 1) (defer 2) (defer 3))
            (fn [& args] args))))

  (is (= [1]
         @(doasync (join (defer 1))
            (fn [& args] args)))))

(deftest handling-async-errors
  (let [res (atom nil)]
    (is (thrown-with-msg? Exception #"BOOM"
          @(doasync
             (join
              (future* (throw (RuntimeException. "BOOM")))
              (future* (Thread/sleep 40) (reset! res :done))))))
    (is (nil? @res))))

;; ==== async-seq

(deftest async-seq-basics
  (are [x] (seq? x)
       (async-seq nil)
       (async-seq [])
       (async-seq [1 2]))

  (are [x y] (= x y)
       (async-seq nil)   ()
       (async-seq [nil]) [nil])

  ;; Unrealized async-seq
  (let [my-seq (async-seq (async-val))]
    (is (identical? my-seq (seq my-seq)))))

(deftest async-seq-with-seq-return-just-like-lazy-seq
  (let [observed? (atom nil)
        my-seq (async-seq (compare-and-set! observed? nil :yep) [:hello])]
    (is (nil? @observed?))
    (is (= (list :hello) (seq my-seq)))
    (is (= :yep @observed?))))

(deftest async-seq-with-simple-synchronous-pipeline
  (are [x y] (= x y)
       (async-seq (doasync nil))   ()
       (async-seq (doasync [nil])) [nil]))

(deftest async-seq-with-simple-async-pipeline
  (let [res (atom [])]
    @(doasync (async-dec-seq 3)
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

(deftest async-seq-throwing-in-fn
  (let [res (atom nil)]
    (is (thrown-with-msg? Exception #"BOOM"
          @(doasync (async-seq (throw (Exception. "BOOM")))
             (fn [v] (reset! res v)))))
    (is (nil? @res))))

;; ==== batch

(deftest batching-regular-seqs
  (are [x] (= [3 2 1] @(batch x))
       (list 3 2 1)
       [3 2 1]
       (lazy-seq (cons 3 (lazy-seq [2 1])))))

(deftest batching-async-seqs
  (are [x] (= [3 2 1] @(batch x))
       (async-dec-seq 3)
       (cons 3 (async-dec-seq 2))
       (lazy-seq (cons 3 (async-dec-seq 2)))))

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
    (put ch :later)
    (close ch)
    (is (= [:hello :goodbye :later] @res))))

;; ==== map*

;; (deftest mapping-async-seq
;;   (is true))

;; ==== AsyncTransferQueue

(defn- incrementing
  ([]  (incrementing 0))
  ([i] (lazy-seq (cons i (incrementing (inc i))))))

(deftest concurrently-accessing-transfer-queue
  (dotimes [_ 5]
    (let [q1 (AsyncTransferQueue. -1)
          q2 (AsyncTransferQueue. -1)
          vs (doall (repeatedly 750 #(.take q2)))]

      (dotimes [i 15]
        (future
          (dotimes [j 50]
            (Thread/sleep 0 200000)
            (.put q1 (+ (* i 50) j))))

        (future
          (dotimes [j 50]
            (doasync (.take q1)
              #(.put q2 %)))))

      (is (= (take 750 (incrementing))
             (sort (map #(deref % 10 -1) vs)))))))
