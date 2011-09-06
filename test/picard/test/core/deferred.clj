(ns picard.test.core.deferred
  (:use
   clojure.test
   picard.core.deferred))

;; ==== Regular objects

(deftest registering-callback-on-object
  (let [dval1 :hello
        dval2 nil
        res   (atom nil)]
    (is (= dval1 (receive dval1 (fn [_ val _] (reset! res val)))))
    (is (= :hello @res))

    (is (nil? (receive dval2 (fn [_ val _] (reset! res val)))))
    (is (nil? @res))))

(deftest rescuing-objects-does-nothing
  (let [res (atom nil)]
    (is (= :hello (rescue :hello Exception #(reset! res %))))
    (is (nil? @res))
    (is (nil? (rescue nil Exception #(reset! res %))))
    (is (nil? @res))))

(deftest calling-finalize-is-invoked
  (let [res (atom nil)]
    (is (= :hello (finalize :hello #(reset! res :one))))
    (is (= :one @res))

    (is (nil? (finalize nil #(reset! res :two))))
    (is (= :two @res))))

;; ==== Realizing deferred values

(deftest successfully-realizing-a-deferred-value
  (let [dval (deferred)
        res  (atom nil)]
    (is (= dval (receive dval (fn [_ val _] (reset! res val)))))
    (is (= dval (put dval :hello)))
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

(deftest realizing-aborted-deferred-values
  (let [dval (deferred)]
    (abort dval (Exception.))
    (is (thrown? Exception (put dval :hello)))))

(deftest realizing-deferred-value-twice
  (let [dval (deferred)
        res  (atom nil)]
    (put dval :one)
    (is (thrown? Exception (put dval :two)))
    (receive dval (fn [_ val _] (reset! res val)))
    (is (= :one @res))))

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
    (rescue dval Exception #(reset! res %))
    (is (= dval (abort dval err)))
    (is (= err @res))))

(deftest aborting-then-registering-handler
  (let [dval (deferred)
        err  (Exception. "TROLLOLOL")
        res  (atom nil)]
    (abort dval err)
    (rescue dval Exception #(reset! res %))
    (is (= err @res))))

(deftest realized-deferred-values-cannot-be-aborted
  (let [dval (deferred)]
    (put dval :hello)
    (is (thrown? Exception (abort dval (Exception. "TROLLOLOL"))))))

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

(deftest thrown-exceptions-in-receive-abort-deferred-value
  (let [res (atom nil)
        err (Exception. "TROLLOLOL")]
    (-> (deferred)
        (receive (fn [_ _ _] (throw err)))
        (rescue Exception #(reset! res %))
        (put :hello))
    (is (= err @res))))

(deftest throws-when-rescue-registered-after-catch-all
  (let [dval (deferred)]
    (catch-all dval identity)
    (is (thrown? Exception (rescue dval Exception identity)))))

;; ==== Finalize statements

(deftest finalize-fn-gets-called-when-realized
  (let [res (atom nil)]
    (-> (deferred)
        (receive (fn [_ val _] (reset! res val)))
        (finalize #(swap! res inc))
        (put 1))
    (is (= 2 @res))))

(deftest finalize-fn-doesnt-get-called-when-no-realize-fn-registered
  (let [res (atom nil)]
    (-> (deferred)
        (finalize #(reset! res :done))
        (put :hello))
    (is (nil? @res))))

(deftest finalize-fn-gets-called-when-aborted
  (let [res (atom nil)]
    (-> (deferred)
        (finalize #(reset! res :done))
        (abort (Exception. "ZOMG")))
    (is (= :done @res))))

(deftest finalize-fn-gets-called-before-rescue
  (let [res (atom nil)]
    (-> (deferred)
        (rescue Exception #(reset! res %))
        (finalize #(swap! res (fn [v] (and v :done))))
        (abort (Exception. "ZOMG")))
    (is (= :done @res))))

(deftest finalize-gets-called-when-receive-throws
  (let [res (atom nil)]
    (-> (deferred)
        (receive (fn [_ _ _] (throw (Exception. "TROLLOLOL"))))
        (finalize #(reset! res :hello))
        (put :run))
    (is (= :hello @res))))

(deftest finalize-gets-called-when-rescue-throws
  (let [res (atom nil)]
    (-> (deferred)
        (rescue Exception (fn [_] (throw (Exception. "TROLLOLOL"))))
        (finalize #(reset! res :one))
        (abort (Exception. "ZOMG")))
    (is (= :one @res))

    (-> (deferred)
        (receive (fn [_ _ _] (throw (Exception. "TROLLOLOL"))))
        (finalize #(reset! res :two))
        (put 1))
    (is (= :two @res))))

(deftest throws-when-rescue-registered-after-finalize
  (let [dval (deferred)]
    (finalize dval (fn []))
    (is (thrown? Exception (rescue dval Exception identity)))))

(deftest throws-when-finalize-registered-after-catch-all
  (let [dval (deferred)]
    (catch-all dval identity)
    (is (thrown? Exception (finalize dval (fn []))))))

;; ==== Catch all statements

(deftest catch-all-statements-invoked-with-unrescued-exceptions
  (let [res (atom nil)
        err (Exception. "TROLLOLOL")]
    (-> (deferred)
        (catch-all #(reset! res %))
        (abort err))
    (is (= err @res))

    (reset! res nil)

    (-> (deferred)
        (abort err)
        (catch-all #(reset! res %)))
    (is (= err @res))))

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
