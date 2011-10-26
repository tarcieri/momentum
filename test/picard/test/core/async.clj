(ns picard.test.core.async
  (:use
   clojure.test
   picard.core.async
   picard.core.deferred))

(defn- deferred-inc
  [i]
  (let [d (deferred)]
    (future
      (Thread/sleep 10)
      (put d (inc i)))
    d))

(deftest simple-pipeline
  (let [res (atom nil)]
    (receive
     (pipeline 1 inc #(if (= 2 %) 3 0))
     #(reset! res %)
     identity)
    (is (= 3 @res))))

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
