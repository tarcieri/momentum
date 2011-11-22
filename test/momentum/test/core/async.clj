(ns momentum.test.core.async
  (:use
   clojure.test
   momentum.core)
  (:import
   [momentum.async
    AsyncTransferQueue
    TimeoutException]
   [java.io
    IOException]))

;; ==== Helpers

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

(defmacro defer
  [& body]
  `(future*
    (Thread/sleep 10)
    ~@body))

(def BOOM (RuntimeException. "BOOM"))

(defn- boom
  [& args]
  (throw BOOM))

(defn- defer-boom
  [& args]
  (defer (boom)))

(defn- compare-and-inc
  [expected]
  (fn [v]
    (if (= expected v)
      (inc v)
      v)))

;; ==== doasync

(defn- async-compare-and-inc
  [expected]
  (fn [v]
    (if (= expected v)
      (defer (inc v))
      v)))

(deftest doasync-with-no-stages
  (are [x y] (= x @y)
       1   (doasync 1)
       [1] (doasync [1])
       nil (doasync nil)
       1   (doasync (defer 1))
       [1] (doasync (defer [1]))
       nil (doasync (defer nil))))

(deftest doasync-succeeding
  (let [share (defer 3)
        q     (atom [])
        fail  (fn [] (swap! q #(conj % :fail)))]

    (are [x] (= 3 @x)

         (doasync share)
         (doasync share)

         (doasync 1
           (compare-and-inc 1)
           (compare-and-inc 2))

         (doasync (defer 1)
           (compare-and-inc 1)
           (compare-and-inc 2))

         (doasync 1
           (async-compare-and-inc 1)
           (compare-and-inc 2))

         (doasync 1
           (async-compare-and-inc 1)
           (async-compare-and-inc 2))

         (doasync (defer 1)
           (async-compare-and-inc 1)
           (async-compare-and-inc 2))

         (doasync 3
           (catch Exception e (fail)))

         (doasync 3
           (catch RuntimeException e (fail))
           (catch Exception e (fail)))

         (doasync 3
           (finally (swap! q #(conj % :1))))

         (doasync 3
           (catch Exception e (fail))
           (finally (swap! q #(conj % :2))))

         (doasync 3
           (catch Exception e (fail))
           (catch RuntimeException e (fail))
           (finally (swap! q #(conj % :3))))

         (doasync 2 inc
           (catch Exception e (fail))))

    (is (= [:1 :2 :3] @q))))

(deftest doasync-aborting
  (let [q    (atom [])
        push (fn [v] (swap! q #(conj % v)))
        fail #(push :fail)]

    (are [x] (thrown-with-msg? Exception #"BOOM" @x)

         (doasync (defer-boom))
         (doasync (defer-boom) fail)
         (doasync 1 boom)
         (doasync 1 boom fail)
         (doasync (defer 1) boom)
         (doasync (defer 1) boom fail)
         (doasync 1 defer-boom)
         (doasync 1 defer-boom fail)
         (doasync (defer 1) defer-boom)
         (doasync (defer 1) defer-boom fail)

         (doasync (defer-boom)
           (finally (push :1)))

         (doasync (defer-boom)
           (catch IOException e))

         (doasync (defer-boom)
           (catch IOException e)
           (finally (push :2)))

         (doasync 1
           (finally (boom)))

         (doasync (defer (throw (Exception. "BLURP")))
           (catch Exception e (boom)))

         (doasync (defer (throw (Exception. "BLURP")))
           (finally (boom))))

    (is (= [:1 :2] @q))))

(deftest doasync-catching-exceptions
  (let [q    (atom [])
        fail (fn [] (swap! q #(conj % :fail)))]

    (are [x] (= 3 @x)

         (doasync (defer-boom)
           (catch Exception e 3))

         (doasync (defer-boom)
           (catch IOException e (fail))
           (catch Exception e 3))

         (doasync (defer-boom)
           (catch Exception e
             (when (= BOOM e)
               3))
           (finally (swap! q #(conj % :1))))

         (doasync 1 boom
           (catch Exception e 3))

         (doasync 1 boom
           (catch Exception e 3)
           (finally (swap! q #(conj % :2))))

         (doasync 1 defer-boom
           (catch Exception e 3))

         (doasync 1 defer-boom
           (catch Exception e 3)
           (finally (swap! q #(conj % :3)))))

    (is (= [:1 :2 :3] @q))))

(deftest nesting-pipelines
  (let [share (defer 3)]
    (are [x] (= 3 @x)
         (doasync (join share share)
           (fn [v1 v2]
             (when (= 3 v1 v2)
               3)))

         (doasync (doasync 3))
         (doasync (doasync 2 inc))
         (doasync (doasync 2 async-inc))
         (doasync (doasync (defer 3)))
         (doasync (doasync (defer 2) inc))
         (doasync (doasync (defer 2) async-inc))

         (doasync 1
           #(doasync %
              (compare-and-inc 1)
              (compare-and-inc 2)))

         ;; Aborted nested pipelines
         (doasync (doasync (defer-boom))
           (catch Exception e
             (when (= BOOM e)
               3))))))

(deftest aborting-in-progress-pipeline-aborts-pending-asyncs
  (let [val (async-val)
        res (atom nil)]
    (abort
     (doasync (defer 1)
       (fn [_] (reset! res :fail)))
     (Exception.))

    (Thread/sleep 50)
    (is (nil? @res)))

  (let [val (async-val)
        res (atom nil)]
    (abort
     (doasync 1
       #(defer (inc %))
       (fn [_] (reset! res :fail)))
     (Exception.))

    (Thread/sleep 50)
    (is (nil? @res))))

(deftest double-realizing-async-val-returns-false
  (let [val (async-val)]
    (is (= true  (put val :hello)))
    (is (= false (put val :fail)))
    (is (= :hello @val)))

  (let [val (async-val)]
    (is (= true  (abort val BOOM)))
    (is (= false (put val :fail)))
    (is (thrown-with-msg? Exception #"BOOM" @val)))

  (let [val (async-val)]
    (put val :hello)
    (is (= false (abort val BOOM)))
    (is (= :hello @val)))

  (let [val (async-val)]
    (abort val BOOM)
    (is (= false (abort val (Exception. "BAM"))))
    (is (thrown-with-msg? Exception #"BOOM" @val))))

;; ==== recur*

(deftest simple-async-recursion-with-primitives
  (are [x] (= 5 @x)
       (doasync 1
         (fn [val]
           (if (< val 5)
             (recur* (inc val))
             val)))

       (doasync (defer 1)
         (fn [val]
           (if (< val 5)
             (recur* (defer (inc val)))
             val)))

       ;; Tail recursive for sync values
       (doasync 100000
         (fn [v]
           (if (> v 5)
             (recur* (dec v))
             v)))))

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

;; ==== channels

(deftest using-channels
  (let [ch (channel)
        sq (seq ch)]
    (put ch :hello)
    (let [[head & tail] sq]
      (is (= :hello head))
      (is (not (realized? tail)))))

  (let [ch (channel)]
    (put ch :hello)
    (let [[head & tail] (seq ch)]
      (is (= :hello head))
      (is (not (realized? tail)))))

  (let [ch (channel)]
    (future
      (dotimes [i 3]
        (Thread/sleep 10)
        (put ch i))
      (close ch))

    (is (= [0 1 2]
           (let [s (seq ch)]
             @(doasync s
                (fn [s] (when s (recur* (next s)))))
             (vec s)))))

  (let [ch (channel)]
    (dotimes [i 3]
      (put ch i))
    (close ch)

    (is (= [0 1 2]
           (let [s (seq ch)]
             @(doasync s
                (fn [s] (when s (recur* (next s)))))
             (vec s)))))

  (let [ch (channel)]
    (dotimes [i 3]
      (put ch i))
    (future
      (Thread/sleep 30)
      (close ch))

    (is (= [0 1 2]
           (let [s (seq ch)]
             @(doasync s
                (fn [s] (when s (recur* (next s)))))
             (vec s))))))

(deftest aborting-channels
  (let [ch  (channel)
        err (Exception.)
        res (atom nil)]
    (abort ch (Exception. "BOOM"))
    (is (thrown-with-msg? Exception #"BOOM"
          @(seq ch))))

  (let [ch  (channel)
        err (Exception.)
        res (atom nil)]

    (doasync (seq ch)
      (fn [[v & more]]
        (reset! res v)
        more)
      (fn [_] (reset! res :fail))
      (catch Exception e
        (compare-and-set! res :hello e)))

    (is (nil? @res))
    (put ch :hello)
    (is (= :hello @res))
    (abort ch err)
    (is (= err @res))))

(deftest observing-an-unrealized-non-blocking-deferred-seq
  (let [ch (channel)]
    (is (thrown? Exception (first (seq ch))))
    (is (thrown? Exception (next (seq ch))))))

(deftest putting-value-into-closed-channel
  (let [ch (channel)]
    (close ch)
    (is (not (put ch :hello)))))

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

;; ==== select

(deftest synchronous-selects
  (are [x] (= (seq x)
              @(doasync (join (select x) [])
                 (fn [vals aggregated]
                   (if-let [[v & more] vals]
                     (recur* more (conj aggregated v))
                     (seq aggregated)))))

       [1]
       [1 2 3 4 5]
       [1 2 3 4 5 6 7 8 9 10]
       nil
       []
       {}
       {:foo 1 :bar 2}))

(deftest asynchronous-selects
  (let [val1 (async-val)
        val2 (async-val)]

    (future
      (Thread/sleep 10)
      (put val2 :hello)
      (Thread/sleep 10)
      (put val1 :world))

    (is (= [:hello :world]
           @(doasync (join (select [val1 val2]) [])
              (fn [vs agg]
                (if-let [[v & more] vs]
                  (recur* more (conj agg v))
                  agg))))))

  (let [val1 (async-val)
        val2 (async-val)]

    (future
      (Thread/sleep 10)
      (put val2 :hello)
      (Thread/sleep 10)
      (put val1 :world))

    (is (= [[:second :hello] [:first :world]]
           @(doasync (join (select {:first val1 :second val2}) [])
              (fn [vs agg]
                (if-let [[v & more] vs]
                  (recur* more (conj agg v))
                  agg)))))))

(deftest handling-async-select-errors
  (let [val1 (async-val)
        val2 (async-val)
        res  (atom nil)]

    (future
      (Thread/sleep 10)
      (abort val2 (Exception. "BOOM")))

    (is (thrown-with-msg? Exception #"BOOM"
          @(doasync (select [val1 val2])
             (fn [_]
               (reset! res :fail)))))

    (is (nil? @res)))

  (let [val1 (async-val)
        val2 (async-val)
        res  (atom nil)]

    (future
      (Thread/sleep 10)
      (abort val2 (Exception. "BOOM")))

    (is (thrown-with-msg? Exception #"BOOM"
          @(doasync (select {:first val1 :second val2})
             (fn [_]
               (reset! res :fail)))))

    (is (nil? @res)))  )

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
             (sort (map #(deref % 50 -1) vs)))))))
