(ns picard.test.core.deferred
  (:use
   clojure.test
   picard.core.deferred))

;; Regular objects

(deftest registering-callback-on-object
  (let [dval1 :hello
        dval2 nil
        res   (atom nil)]
    (receive dval1 (fn [_ val _] (reset! res val)))
    (is (= :hello @res))

    (receive dval2 (fn [_ val _] (reset! res val)))
    (is (nil? @res))))

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

(deftest calling-wait
  (let [dval (deferred)
        res  (atom nil)]

    (future
      (Thread/sleep 20)
      (put dval :hello))

    (receive dval (fn [_ val _] (reset! res val)))
    (is (wait dval))
    (is (= :hello @res))))

(deftest calling-wait-when-already-realized
  (let [dval (deferred)
        res  (atom nil)]
    (put dval :hello)
    (receive dval (fn [_ val _] (reset! res val)))
    (is (= true (wait dval)))))

(deftest wait-call-times-out
  (let [dval   (deferred)
        now    (System/currentTimeMillis)
        first  (future
                 (wait dval 20)
                 (- (System/currentTimeMillis) now))
        second (future
                 (wait dval 50)
                 (- (System/currentTimeMillis) now))]
    ;; Timers aren't precise
    (is (< 19 @first @second 80))))

;; There can only be 1 receive function.
;;   this is because it doesn't make sense
;;   to add additional receive functions on
;;   channels once values have already passed
;;   through.
;; Catching
;; * Aborting materialized deferred value
;; * Materializing aborted deferred value
;; * Only one catch block gets invoked
;; * On abort
;;   * Immedietly check all registered handlers.
;;   * Only a single handler gets invoked
;;   * If no handler is invoked, do nothing.
;;   * When matching handler registered, invoke
;;   * When another matching handler registered, do nothing.
;; * Finally callback gets called on success / abort
