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

(defn- async-inc-seq [i]
  (async-seq
    (future*
     (Thread/sleep 5)
     (when (< i 0)
       (cons i (async-inc-seq (inc i)))))))

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

(defn- async-compare-and-inc
  [expected]
  (fn [v]
    (if (= expected v)
      (defer (inc v))
      v)))

;; ==== deref

(deftest deref-with-timeout-works
  (is (= :timeout (deref (async-val) 50 :timeout))))

;; ==== doasync

(deftest doasync-with-no-stages
  (are [x y] (= x y)
       1   (doasync 1)
       [1] (doasync [1])
       nil (doasync nil))

  (are [x y] (= x @y)
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
           (finally [win?] (swap! q #(conj % :2))))

         (doasync 3
           (catch Exception e (fail))
           (finally (swap! q #(conj % :3))))

         (doasync 3
           (catch Exception e (fail))
           (catch RuntimeException e (fail))
           (finally (swap! q #(conj % :4))))

         (doasync 2 inc
           (catch Exception e (fail))))

    (is (= [:1 :2 :3 :4] @q))))

(deftest doasync-downstream-aborting
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

(deftest doasync-upstream-aborting
  (let [q    (atom [])
        push (fn [v] (swap! q #(conj % v)))
        fail (fn [& args] (push :fail))]

    (let [seed (defer 1)
          val  (doasync seed fail)]
      (is (= true (abort val (Exception. "BOOM"))))
      (is (aborted? seed))
      (is (thrown-with-msg? Exception #"BOOM" @val)))

    (let [step (defer 2)
          val  (doasync 1 (constantly step) fail)]
      (is (= true (abort val (Exception. "BOOM"))))
      (is (aborted? step))
      (is (thrown-with-msg? Exception #"BOOM" @val)))

    (let [seed (defer 1)
          step (future* (Thread/sleep 50) 2)
          val  (doasync seed (constantly step) fail)]
      @seed
      (is (= true (abort val (Exception. "BOOM"))))
      (is (aborted? step))
      (is (thrown-with-msg? Exception #"BOOM" @val)))

    (abort
     (doasync (defer 1)
       (catch Exception _ (push :1)))
     (Exception.))

    (abort
     (doasync (defer 1)
       (finally (push :2)))
     (Exception.))

    (Thread/sleep 50)
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

         (doasync (doasync (defer 3)))
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

(deftest doasync-recursion
  (let [err (Exception. "BOOM")]
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
               v)))

         ;; Handles aborted async vals
         (doasync 1
           (fn [v]
             (when v ;; Prevents infinite loop when buggy
               (let [val (async-val)]
                 (abort val err)
                 (recur* val))))
           (catch Exception e
             (when (= err e)
               5))))))

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

(deftest async-seq-upstream-aborting
  (let [q    (atom [])
        push (fn [v] (swap! q #(conj % v)))
        fail (fn [& args] (push :fail))]

    (let [seed (defer 1)
          val  (async-seq
                 (doasync seed fail))]
      (is (= true (abort val (Exception. "BOOM"))))
      (is (aborted? seed))
      (is (thrown-with-msg? Exception #"BOOM" @val)))))

(deftest blocking-async-seqs
  (are [x] (= [3 2 1] x)
       (blocking [3 2 1])
       (blocking (async-dec-seq 3))
       (blocking (cons 3 (async-dec-seq 2))))

  (is (= nil (blocking nil)))
  (is (= [1] (blocking [1])))
  (is (= [1] (blocking (cons 1 (lazy-seq nil))))))

(deftest aborting-unrealized-async-seqs
  (let [s (async-seq (async-val))]
    (is (= true (abort s BOOM)))
    (is (aborted? s))
    (is (= false (abort s BOOM)))))

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

(deftest observing-an-unrealized-non-blocking-async-seq
  (let [ch (channel)]
    (is (thrown? Exception (first (seq ch))))
    (is (thrown? Exception (next (seq ch))))))

(deftest putting-value-into-closed-channel
  (let [ch (channel)]
    (close ch)
    (is (not (put ch :hello)))))

(deftest blocking-channel-seqs-already-closed
  (let [ch (channel)]
    (put ch :hello)
    (put ch :world)
    (close ch)
    (is (= (blocking (seq ch))
           [:hello :world]))))

(deftest blocking-channel-seqs-will-be-closed
  (let [ch (channel)]
    (future
      (Thread/sleep 10)
      (put ch :hello)
      (Thread/sleep 10)
      (put ch :world)
      (Thread/sleep 10)
      (close ch))

    (is (= (blocking (seq ch))
           [:hello :world]))))

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

(deftest aborting-joins
  ;; Downstream aborting
  (let [val1 (future* (Thread/sleep 20) 1)]
    (is (thrown-with-msg? Exception #"BOOM"
          @(join val1 (defer (boom)))))
    (is (aborted? val1)))

  ;; Upstream aborting
  (let [val1 (defer 1)
        val2 (defer 2)]
    (is (= true (abort (join val1 val2 3) (Exception.))))
    (is (aborted? val1))
    (is (aborted? val2)))

  (let [val1 (defer 1)
        val2 (async-val)]
    (put val2 :hello)
    (is (= true (abort (join val1 val2) (Exception.))))
    (is (aborted? val1))
    (is (not (aborted? val2))))

  (let [val1 (defer 1)
        val2 (defer [1 2 3])
        val3 (async-seq val2)]
    (is (= true (abort (join val1 val3) (Exception.))))
    (is (aborted? val1 val2 val3))))

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

;; ==== concat*

(deftest simple-concat*
  (are [x y] (= x y)
       (concat*)             ()
       (concat* [])          ()
       (concat* [1 2])       '(1 2)
       (concat* [1 2] [3 4]) '(1 2 3 4)

       (blocking
        (concat*
         (async-dec-seq 2)
         (async-inc-seq -1)))
       '(2 1 -1)))

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

;; ==== Various helpers

(deftest async-success?-aborted?-realized?
  (let [val (async-val)]
    (is (not (realized? val)))
    (is (not (success? val)))
    (is (not (aborted? val)))

    (put val :hello)
    (is (realized? val))
    (is (success? val))
    (is (not (aborted? val))))

  (let [val (async-val)]
    (abort val BOOM)
    (is (realized? val))
    (is (not (success? val)))
    (is (aborted? val))))

;; ==== splice

(deftest asynchronous-splice
  (let [val1 (async-val)
        val2 (async-val)]

    (future
      (Thread/sleep 10)
      (put val2 :hello)
      (Thread/sleep 10)
      (put val1 :world))

    (is (= [[:second :hello] [:first :world]]
           @(doasync (join (splice {:first val1 :second val2}) [])
              (fn [vs agg]
                (if-let [[v & more] vs]
                  (recur* more (conj agg v))
                  agg)))))))

(deftest handling-async-splice-errors
  (let [val1 (async-val)
        val2 (defer-boom)
        res  (atom nil)]

    (is (thrown-with-msg? Exception #"BOOM"
          @(doasync (splice [:val1 val1] [:val2 val2])
             (fn [_]
               (reset! res :fail)))))

    (is (aborted? val1))

    (is (nil? @res)))

  (let [val1 (async-val)
        val2 (defer-boom)
        res  (atom nil)]

    (is (thrown-with-msg? Exception #"BOOM"
          @(doasync (splice {:first val1 :second val2})
             (fn [_]
               (reset! res :fail)))))

    (is (nil? @res))))

(deftest aborting-splices
  (let [val1 (async-val)
        val2 (async-val)
        res  (atom nil)]

    (abort
     (doasync (splice [:val1 val1] [:val2 val2])
       (fn [_]
         (reset! res :fail)))
     BOOM)

    (is (aborted? val1))
    (is (aborted? val2))))

(deftest splice-two-synchronous-seqs
  (is (= (map
          (fn [[k v]] [k v])
          (splice {:a [1 2] :b [4 5]}))
         (list [:a 1] [:a 2] [:b 4] [:b 5]))))

(deftest splice-two-asynchronous-seqs
  (let [ch1     (channel)
        ch2     (channel)
        spliced (splice {:ch1 (seq ch1) :ch2 (seq ch2)})]

    (defer (put ch1 :omg))

    @(doasync spliced
       (fn [[el & more]]
         (is (= [:ch1 :omg] el))
         (defer (put ch2 :w0t))
         more)
       (fn [[el & more]]
         (is (= [:ch2 :w0t] el))
         (close ch1)
         (close ch2)
         more))))

(deftest removing-spliced-seqs
  (let [ch1     (channel)
        ch2     (channel)
        spliced (splice {:ch1 (seq ch1) :ch2 (seq ch2)})
        spliced (dissoc spliced :ch1)]

    (put ch1 :zomg)
    (is (= :win (deref spliced 30 :win)))))

(deftest accessing-the-spliced-seqs
  (let [coll    (seq [1 2 3])
        spliced (splice {:ch1 coll})]
    (is (= coll (get spliced :ch1)))))

(deftest pulling-the-last-item-in-a-spliced-seq-removes-the-key
  (let [ch (channel)
        spliced (splice {:ch (seq ch)})]
    (put ch :omg)
    (close ch)
    (doasync spliced
      (fn [[[k v] & more]]
        (is (= [k v] [:ch :omg]))
        (is (not (nil? (get more :ch))))
        more)
      (fn [coll]
        (is (nil? coll))
        (is (nil? (get coll :ch)))))))

(deftest closing-spliced-seq-member-followed-by-more-events
  (let [ch1     (channel)
        ch2     (channel)
        spliced (splice {:ch1 (seq ch1) :ch2 (seq ch2)})]

    (future
      (Thread/sleep 10)
      (put ch1 :one)
      (Thread/sleep 10)
      (close ch1)
      (Thread/sleep 10)
      (put ch2 :two)
      (Thread/sleep 10)
      (close ch2))

    @(doasync spliced
       (fn [[el & more]]
         (is (= el [:ch1 :one]))
         more)
       (fn [[el & more]]
         (is (= el [:ch2 :two]))
         more)
       (fn [coll]
         (is (nil? coll))))))

(deftest an-error-in-one-spliced-value-aborts-all
  (let [ch1     (channel)
        ch2     (channel)
        seq1    (seq ch1)
        seq2    (seq ch2)]

    (future
      (Thread/sleep 10)
      (abort ch1 BOOM))

    (is (thrown-with-msg? Exception #"BOOM"
          @(doasync (splice {:ch1 seq1 :ch2 seq2}))))

    (is (aborted? seq1))
    (is (aborted? seq2))))

(deftest simple-blocking-spliced-async-seq
  (let [ch1 (channel)
        ch2 (channel)]

    (future
      (Thread/sleep 10)
      (put ch1 :one)
      (Thread/sleep 10)
      (put ch2 :two)
      (Thread/sleep 10)
      (close ch1)
      (Thread/sleep 10)
      (close ch2))

    (is (= (blocking (splice {:ch1 (seq ch1) :ch2 (seq ch2)}))
           [[:ch1 :one] [:ch2 :two]]))))

(deftest blocking-spliced-seq-closed
  (let [ch1 (channel)
        ch2 (channel)]

    (future
      (Thread/sleep 10)
      (close ch1)
      (Thread/sleep 10)
      (close ch2))

    (is (= [] (blocking (splice {:ch1 (seq ch1) :ch2 (seq ch2)}))))))

(deftest blocking-spliced-seq-assoc
  (let [ch1 (channel)
        ch2 (channel)]

    (future
      (Thread/sleep 10)
      (put ch1 :one)
      (close ch1)

      (Thread/sleep 10)
      (put ch2 :two)
      (close ch2))

    (is (= [[:ch1 :one] [:ch2 :two]]
           (assoc (blocking (splice {:ch1 (seq ch1)}))
             :ch2 (seq ch2))))))

(deftest blocking-spliced-seq-dissoc
  (let [ch1 (channel)
        ch2 (channel)]

    (future
      (Thread/sleep 10)
      (put ch1 :one)
      (close ch1))

    (is (= [[:ch1 :one]]
           (dissoc
            (blocking (splice {:ch1 (seq ch1) :ch2 (seq ch2)}))
            :ch2)))))

(deftest prioritized-async-seqs
  (let [ch1 (channel)
        ch2 (channel)
        sq1 (seq ch1)
        sq2 (seq ch2)]

    (put ch1 :one)
    (put ch2 :two)

    (future
      (Thread/sleep 10)
      (close ch1)
      (close ch2))

    (is (= (blocking (splice [:ch2 sq2] [:ch1 sq1]))
           [[:ch2 :two] [:ch1 :one]]))

    (is (= (blocking (splice [:ch1 sq1] [:ch2 sq2]))
           [[:ch1 :one] [:ch2 :two]]))))

(deftest uses-lower-priority-events-when-first-available
  (let [ch1 (channel)
        ch2 (channel)]

    (future
      (Thread/sleep 10)
      (put ch2 :zomg))

    (is (= [:ch2 :zomg] (first (blocking (splice [:ch1 (seq ch1)] [:ch2 (seq ch2)])))))))
