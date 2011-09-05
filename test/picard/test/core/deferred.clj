(ns picard.test.core.deferred
  (:use
   clojure.test
   picard.core.deferred))

;; ==== Regular objects

(deftest registering-callback-on-object
  (let [dval1 :hello
        dval2 nil
        res   (atom nil)]
    (receive dval1 (fn [_ val _] (reset! res val)))
    (is (= :hello @res))

    (receive dval2 (fn [_ val _] (reset! res val)))
    (is (nil? @res))))

(deftest catching-objects-does-nothing
  (let [res (atom nil)]
    (catch :hello Exception #(reset! res %))
    (is (nil? @res))
    (catch nil Exception #(reset! res %))
    (is (nil? @res))))

(deftest calling-finally-is-invoked
  (let [res (atom nil)]
    (finally :hello #(reset! res :one))
    (is (= :one @res))

    (finally nil #(reset! res :two))
    (is (= :two @res))))

(deftest waiting-for-objects
  (let [dval1 :hello
        dval2 nil
        res   (atom nil)]
    ;; Le sigh, timing tests
    (future
      (Thread/sleep 10)
      (reset! res :fail))

    (is (wait dval1))
    (is (nil? @res))

    (is (wait dval2))
    (is (nil? @res))))

;; ==== Realizing deferred values

(deftest successfully-realizing-a-deferred-value
  (let [dval (deferred)
        res  (atom nil)]
    (receive dval (fn [_ val _] (reset! res val)))
    (put dval :hello)
    (is (= :hello @res))))

(deftest receiving-from-realized-deferred-value
  (let [dval (deferred)
        res  (atom nil)]
    (put dval :hello)
    (receive dval (fn [_ val _] (reset! res val)))
    (is (= :hello @res))))

(deftest registering-nil-callback
  (let [dval (deferred)]
    (is (thrown? NullPointerException (receive dval nil)))))

(deftest registering-receive-callback-twice
  (let [dval (deferred)]
    (receive dval (fn [& _]))
    (is (thrown? Exception (receive dval (fn [& _]))))
    (put dval :hello)
    (is (thrown? Exception (receive dval (fn [& _]))))))

;; ==== Aborting deferred values

(deftest aborting-deferred-value-calls-catch-handler
  (let [dval (deferred)
        err  (Exception. "TROLLOLOL")
        res  (atom nil)]
    (catch dval Exception (fn [err] (reset! res err)))
    (abort dval err)
    (is (= err @res))))

(deftest realized-deferred-values-cannot-be-aborted
  (let [dval (deferred)]
    (put dval :hello)
    (is (thrown? Exception (abort dval (Exception. "TROLLOLOL"))))))

(deftest aborted-deferred-values-cannot-be-realized
  (let [dval (deferred)]
    (abort dval (Exception.))
    (is (thrown? Exception (put dval :hello)))))

(deftest only-one-catch-statement-gets-called
  (let [dval (deferred)
        res1 (atom nil)
        res2 (atom nil)]
    (catch dval Exception #(reset! res1 %))
    (catch dval Exception #(reset! res2 %))
    (abort dval (Exception.))
    (is (instance? Exception @res1))
    (is (nil? @res2))))

(deftest does-not-call-matching-catch-block-if-abort-already-handled
  (let [dval (deferred)
        res  (atom nil)]
    (catch dval Exception (fn [& _]))
    (abort dval (Exception.))
    (catch dval Exception #(reset! res %))
    (is (nil? @res))))

(deftest skips-non-matching-catch-callbacks
  (let [dval (deferred)
        res  (atom nil)]
    (catch dval NullPointerException (fn [_] (reset! res :fail)))
    (catch dval Exception (fn [_] (reset! res :win)))
    (abort dval (Exception.))))

;; ==== Finally statements

(deftest finally-fn-gets-called-when-realized
  (let [dval (deferred)
        res  (atom nil)]
    (receive dval (fn [_ val _] (reset! res val)))
    (finally dval #(swap! res inc))
    (put dval 1)
    (is (= 2 @res))))

(deftest finally-fn-doesnt-get-called-when-no-realize-fn-registered
  (let [dval (deferred)
        res  (atom nil)]
    (finally dval #(reset! res :done))
    (put dval :hello)
    (is (nil? @res))))

;; TODO: What happens when catch registered after finally?

;; (deftest finally-fn-gets-called-when-aborted
;;   (let [dval (deferred)
;;         res  (atom nil)]
;;     (finally dval #(reset! res :done))
;;     (abort dval (Exception. "ZOMG"))
;;     (is (= :done @res))))

(deftest finally-fn-gets-called-before-catch
  (let [dval (deferred)
        res  (atom nil)]
    (catch dval Exception #(reset! res %))
    (finally dval #(swap! res (fn [v] (and v :done))))
    (abort dval (Exception. "ZOMG"))
    (is (= :done @res))))

;; ==== Waiting on deferred values

;; TODO: Unbreak waiting

;; (deftest calling-wait
;;   (let [dval (deferred)
;;         res  (atom nil)]
;;     (future
;;       (Thread/sleep 20)
;;       (put dval :hello))

;;     (receive dval (fn [_ val _] (reset! res val)))
;;     (is (wait dval))
;;     (is (= :hello @res))))

;; (deftest calling-wait-then-aborted
;;   (let [dval (deferred)
;;         res  (atom nil)]
;;     (future
;;       (Thread/sleep 20)
;;       (abort dval (Exception. "TROLLOLOL")))

;;     (catch dval Exception #(reset! res %))
;;     (is (wait dval))
;;     (is (instance? Exception @res))))

;; (deftest calling-wait-when-already-realized
;;   (let [dval (deferred)
;;         now  (System/currentTimeMillis)]
;;     (put dval :hello)
;;     (is (wait dval))
;;     (is (> 2 (- (System/currentTimeMillis) now)))))

;; (deftest calling-wait-when-already-aborted
;;   (let [dval (deferred)
;;         now  (System/currentTimeMillis)]
;;     (abort dval (Exception.))
;;     (is (wait dval))
;;     (is (> 2 (- (System/currentTimeMillis) now)))))

;; (deftest wait-call-times-out
;;   (let [dval   (deferred)
;;         now    (System/currentTimeMillis)
;;         first  (future
;;                  (wait dval 20)
;;                  (- (System/currentTimeMillis) now))
;;         second (future
;;                  (wait dval 50)
;;                  (- (System/currentTimeMillis) now))]
;;     ;; Timers aren't precise
;;     (is (< 19 @first @second 80))))

;; Exception propagation
;; * Cannot register any finally callbacks once a catch-all callback
;;   is registered.
;; * Exception thrown in catch block gets bubled up
;; * Exception thrown in catch block does not get caught
;;   in next catch block
;; * Exception thrown in finally gets bubbled up
;; * Exceptions thrown in finally get priority over exceptions thrown
;;   in catch
