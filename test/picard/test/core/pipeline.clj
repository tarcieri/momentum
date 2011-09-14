(ns picard.test.core.pipeline
  (:use
   clojure.test
   picard.core.deferred
   picard.core.pipeline))

(defn- async-inc
  [val]
  (let [dval (deferred)]
    (future
      (Thread/sleep 20)
      (put dval (inc val)))
    dval))

(deftest simple-synchronous-pipeline
  (let [res (atom nil)]
    (-> (build-pipeline inc inc)
        (put 1)
        (receive #(reset! res %)))
    (is (= 3 @res))

    (-> (build-pipeline inc inc)
        (receive #(reset! res %))
        (put 501))
    (is (= 503 @res))

    (-> (pipeline 101 inc inc)
        (receive #(reset! res %)))
    (is (= 103 @res))))

(deftest simple-asynchronous-pipeline
  (let [res (promise)]
    (-> (pipeline 1 async-inc async-inc)
        (receive #(res %)))
    (is (= 3 @res))))

(deftest explicitly-aborting-pipeline
  (let [res (promise)
        err (Exception.)]
    (-> (build-pipeline inc inc)
        (rescue Exception #(res %))
        (abort err))
    (is (= err @res))))

(deftest rescuing-pipeline
  (let [res (promise)
        err (Exception.)]
    (pipeline
     1 inc (fn [_] (throw err))
     (catch Exception e (res e)))
    (is (= err @res))))

(deftest multiple-catch-clauses
  (let [res (promise)
        err (IllegalArgumentException.)]
    (pipeline
     1 inc (fn [_] (throw err))
     (catch IllegalArgumentException e (res e))
     (catch Exception _ (res :fail)))
    (is (= err @res))))

(deftest finally-clauses-are-invoked
  (let [res1 (promise)
        res2 (promise)]
    (-> (pipeline
         1 inc inc
         (finally (res1 :hello)))
        (receive identity))
    (is (= :hello @res1))

    (-> (pipeline
         1 inc (fn [_] (throw (Exception.)))
         (finally (res2 :world)))
        (receive identity))
    (is (= :world @res2))
    ))

(deftest nesting-pipelines
  (let [res (promise)]
    (-> (pipeline
         1
         #(pipeline % async-inc async-inc)
         inc)
        (receive #(res %)))
    (is (= 4 @res))))

(deftest rescuing-exceptions-from-nested-pipeline
  (let [res1 (promise)
        res2 (promise)
        err  (Exception.)]
    (-> (build-pipeline
         async-inc
         #(pipeline % async-inc (fn [_] (throw err))))
        (put 1)
        (rescue Exception #(res1 %)))
    (is (= err @res1))))
