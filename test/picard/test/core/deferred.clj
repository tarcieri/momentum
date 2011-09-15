(ns picard.test.core.deferred
  (:use
   clojure.test
   picard.core.deferred))

;; ==== Regular objects

(deftest registering-callback-on-object
  (let [dval1 :hello
        dval2 nil
        res   (atom nil)]
    (is (= dval1 (receive dval1 #(reset! res %))))
    (is (= :hello @res))

    (is (nil? (receive dval2 #(reset! res %))))
    (is (nil? @res))))

(deftest catching-objects-does-nothing
  (let [res (atom nil)]
    (is (= :hello (catch* :hello Exception #(reset! res %))))
    (is (nil? @res))
    (is (nil? (catch* nil Exception #(reset! res %))))
    (is (nil? @res))))

(deftest calling-finally-is-invoked
  (let [res (atom nil)]
    (is (= :hello (finally* :hello #(reset! res :one))))
    (is (= :one @res))

    (is (nil? (finally* nil #(reset! res :two))))
    (is (= :two @res))))

;; ==== Realizing deferred values

(deftest successfully-realizing-a-deferred-value
  (let [dval (deferred)
        res  (atom nil)]
    (is (= dval (receive dval #(reset! res %))))
    (is (= dval (put dval :hello)))
    (is (= :hello @res))))

(deftest receiving-from-realized-deferred-value
  (let [dval (deferred)
        res  (atom nil)]
    (put dval :hello)
    (receive dval #(reset! res %))
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
    (receive dval #(reset! res %))
    (is (= :one @res))))

(deftest registering-receive-callback-twice
  (let [dval (deferred)]
    (receive dval (fn [_]))
    (is (thrown? Exception (receive dval (fn [_]))))
    (put dval :hello)
    (is (thrown? Exception (receive dval (fn [_]))))))

;; ==== Aborting deferred values

(deftest aborting-deferred-value-calls-catch-handler
  (let [dval (deferred)
        err  (Exception. "TROLLOLOL")
        res  (atom nil)]
    (catch* dval Exception #(reset! res %))
    (is (= dval (abort dval err)))
    (is (= err @res))))

(deftest aborting-then-registering-handler
  (let [dval (deferred)
        err  (Exception. "TROLLOLOL")
        res  (atom nil)]
    (abort dval err)
    (catch* dval Exception #(reset! res %))
    (is (= err @res))))

(deftest realized-deferred-values-cannot-be-aborted
  (let [dval (deferred)]
    (put dval :hello)
    (is (thrown? Exception (abort dval (Exception. "TROLLOLOL"))))))

(deftest only-one-catch-statement-gets-called
  (let [dval (deferred)
        res1 (atom nil)
        res2 (atom nil)]
    (catch* dval Exception #(reset! res1 %))
    (catch* dval Exception #(reset! res2 %))
    (abort dval (Exception.))
    (is (instance? Exception @res1))
    (is (nil? @res2))))

(deftest does-not-call-matching-catch-block-if-abort-already-handled
  (let [dval (deferred)
        res  (atom nil)]
    (catch* dval Exception (fn [& _]))
    (abort dval (Exception.))
    (catch* dval Exception #(reset! res %))
    (is (nil? @res))))

(deftest skips-non-matching-catch-callbacks
  (let [dval (deferred)
        res  (atom nil)]
    (catch* dval NullPointerException (fn [_] (reset! res :fail)))
    (catch* dval Exception (fn [_] (reset! res :win)))
    (abort dval (Exception.))))

(deftest thrown-exceptions-in-receive-abort-deferred-value
  (let [res (atom nil)
        err (Exception. "TROLLOLOL")]
    (-> (deferred)
        (receive (fn [_] (throw err)))
        (catch* Exception #(reset! res %))
        (put :hello))
    (is (= err @res))))

(deftest throws-when-catch-registered-after-catch-all
  (let [dval (deferred)]
    (catch-all dval identity)
    (is (thrown? Exception (catch* dval Exception identity)))))

;; ==== Finally statements

(deftest finally-fn-gets-called-when-realized
  (let [res (atom nil)]
    (-> (deferred)
        (receive #(reset! res %))
        (finally* #(swap! res inc))
        (put 1))
    (is (= 2 @res))))

(deftest finally-fn-doesnt-get-called-when-no-realize-fn-registered
  (let [res (atom nil)]
    (-> (deferred)
        (finally* #(reset! res :done))
        (put :hello))
    (is (nil? @res))))

(deftest finally-fn-gets-called-when-aborted
  (let [res (atom nil)]
    (-> (deferred)
        (finally* #(reset! res :done))
        (abort (Exception. "ZOMG")))
    (is (= :done @res))))

(deftest finally-fn-gets-called-before-catch
  (let [res (atom nil)]
    (-> (deferred)
        (catch* Exception #(reset! res %))
        (finally* #(swap! res (fn [v] (and v :done))))
        (abort (Exception. "ZOMG")))
    (is (= :done @res))))

(deftest finally-gets-called-when-receive-throws
  (let [res (atom nil)]
    (-> (deferred)
        (receive (fn [_] (throw (Exception. "TROLLOLOL"))))
        (finally* #(reset! res :hello))
        (put :run))
    (is (= :hello @res))))

(deftest finally-gets-called-when-catch-throws
  (let [res (atom nil)]
    (-> (deferred)
        (catch* Exception (fn [_] (throw (Exception. "TROLLOLOL"))))
        (finally* #(reset! res :one))
        (abort (Exception. "ZOMG")))
    (is (= :one @res))

    (-> (deferred)
        (receive (fn [_] (throw (Exception. "TROLLOLOL"))))
        (finally* #(reset! res :two))
        (put 1))
    (is (= :two @res))))

(deftest throws-when-catch-registered-after-finally
  (let [dval (deferred)]
    (finally* dval (fn []))
    (is (thrown? Exception (catch* dval Exception identity)))))

(deftest throws-when-finally-registered-after-catch-all
  (let [dval (deferred)]
    (catch-all dval identity)
    (is (thrown? Exception (finally* dval (fn []))))))

;; ==== Catch all statements

(deftest catch-all-statement-invoked-with-uncaught-exceptions
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

(deftest catch-all-statement-not-invoked-with-caught-exceptions
  (let [res (atom nil)]
   (-> (deferred)
       (catch* Exception identity)
       (catch-all #(reset! res %))
       (abort (Exception. "TROLLOLOL")))
   (is (nil? @res))

   (-> (deferred)
       (abort (Exception. "TROLLOLOL"))
       (catch* Exception identity)
       (catch-all #(reset! res %)))
   (is (nil? @res))))

(deftest finally-invoked-before-catch-all
  (let [res (atom nil)
        err (Exception. "TROLLOLOL")]
    (-> (deferred)
        (finally* #(reset! res :one))
        (catch-all #(reset! res %))
        (abort err))
    (is (= err @res))))

(deftest catch-all-called-with-uncaught-exception
  (let [res (atom nil)
        err (Exception. "TROLLOLOL")]
    (-> (deferred)
        (catch* Exception (fn [_] (throw err)))
        (catch-all #(reset! res %))
        (abort (Exception. "LULZ")))
    (is (= err @res))

    (reset! res nil)

    (-> (deferred)
        (receive (fn [_] (throw (Exception. "LULZ"))))
        (catch* Exception (fn [_] (throw err)))
        (catch-all #(reset! res %))
        (put 1))
    (is (= err @res))

    (reset! res nil)

    (-> (deferred)
        (abort (Exception. "GAGA"))
        (catch* Exception (fn [_] (throw err)))
        (finally* (fn []))
        (catch-all #(reset! res %)))
    (is (= err @res))))

(deftest catch-all-called-with-finally-exception
  (let [res (atom nil)
        err (Exception. "TROLLOLOL")]
    (-> (deferred)
        (catch* Exception (fn [_] (throw "OH NO")))
        (finally* #(throw err))
        (abort (Exception. "LULZ"))
        (catch-all #(reset! res %)))
    (is (= err @res))))
