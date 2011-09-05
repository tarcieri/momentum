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

(deftest rescuing-objects-does-nothing
  (let [res (atom nil)]
    (rescue :hello Exception #(reset! res %))
    (is (nil? @res))
    (rescue nil Exception #(reset! res %))
    (is (nil? @res))))

(deftest calling-finalize-is-invoked
  (let [res (atom nil)]
    (finalize :hello #(reset! res :one))
    (is (= :one @res))

    (finalize nil #(reset! res :two))
    (is (= :two @res))))

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

(deftest aborting-deferred-value-calls-rescue-handler
  (let [dval (deferred)
        err  (Exception. "TROLLOLOL")
        res  (atom nil)]
    (rescue dval Exception (fn [err] (reset! res err)))
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

(deftest only-one-rescue-statement-gets-called
  (let [dval (deferred)
        res1 (atom nil)
        res2 (atom nil)]
    (rescue dval Exception #(reset! res1 %))
    (rescue dval Exception #(reset! res2 %))
    (abort dval (Exception.))
    (is (instance? Exception @res1))
    (is (nil? @res2))))

(deftest does-not-call-matching-rescue-block-if-abort-already-handled
  (let [dval (deferred)
        res  (atom nil)]
    (rescue dval Exception (fn [& _]))
    (abort dval (Exception.))
    (rescue dval Exception #(reset! res %))
    (is (nil? @res))))

(deftest skips-non-matching-rescue-callbacks
  (let [dval (deferred)
        res  (atom nil)]
    (rescue dval NullPointerException (fn [_] (reset! res :fail)))
    (rescue dval Exception (fn [_] (reset! res :win)))
    (abort dval (Exception.))))

;; ==== Finalize statements

(deftest finalize-fn-gets-called-when-realized
  (let [dval (deferred)
        res  (atom nil)]
    (receive dval (fn [_ val _] (reset! res val)))
    (finalize dval #(swap! res inc))
    (put dval 1)
    (is (= 2 @res))))

(deftest finalize-fn-doesnt-get-called-when-no-realize-fn-registered
  (let [dval (deferred)
        res  (atom nil)]
    (finalize dval #(reset! res :done))
    (put dval :hello)
    (is (nil? @res))))

;; TODO: What happens when rescue registered after finalize?

;; (deftest finalize-fn-gets-called-when-aborted
;;   (let [dval (deferred)
;;         res  (atom nil)]
;;     (finalize dval #(reset! res :done))
;;     (abort dval (Exception. "ZOMG"))
;;     (is (= :done @res))))

(deftest finalize-fn-gets-called-before-rescue
  (let [dval (deferred)
        res  (atom nil)]
    (rescue dval Exception #(reset! res %))
    (finalize dval #(swap! res (fn [v] (and v :done))))
    (abort dval (Exception. "ZOMG"))
    (is (= :done @res))))

(deftest throws-when-rescue-registered-after-finalize
  (let [dval (deferred)]
    (finalize dval (fn []))
    (is (thrown? Exception (rescue dval Exception identity)))))

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

;;     (rescue dval Exception #(reset! res %))
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
;; * Cannot register any finalize callbacks once a catch-all callback
;;   is registered.
;; * Exception thrown in catch block gets bubled up
;; * Exception thrown in catch block does not get caught
;;   in next catch block
;; * Exception thrown in finalize gets bubbled up
;; * Exceptions thrown in finalize get priority over exceptions thrown
;;   in catch
