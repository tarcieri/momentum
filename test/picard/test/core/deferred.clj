(ns picard.test.core.deferred
  (:use
   clojure.test
   picard.core.deferred))

;; ==== Regular objects

(deftest registering-callback-on-object
  (let [dval1 :hello
        res   (atom nil)]

    (is (= dval1
           (receive dval1
                    #(compare-and-set! res nil %)
                    (fn [_] (reset! res :fail)))))
    (is (= :hello @res))

    (is (nil? (receive nil
                       #(compare-and-set! res :hello %)
                       (fn [_] (reset! res :fail)))))
    (is (nil? @res))))

;; ==== Deferred values

(deftest successfully-realizing-a-deferred-value
  (let [dval (deferred)
        res  (atom nil)]
    (is (= dval (receive dval
                         #(compare-and-set! res nil %)
                         (fn [_] (reset! res :fail)))))
    (is (nil? @res))
    (is (= dval (put dval :hello)))
    (is (= :hello @res)))

  (let [dval (deferred)
        res  (atom nil)]

    (is (= dval (put dval :hello)))
    (receive
     dval
     #(compare-and-set! res nil %)
     (fn [_] (reset! res :fail)))
    (is (= :hello @res))))

(deftest aborting-deferred-values
  (let [dval (deferred)
        err  (Exception.)
        res  (atom nil)]
    (receive dval
             (fn [_] (reset! res :fail))
             #(compare-and-set! res nil %))
    (abort dval err)
    (is (= err @res)))

  (let [dval (deferred)
        err  (Exception.)
        res  (atom nil)]
    (abort dval err)
    (receive dval
             (fn [_] (reset! res :fail))
             #(compare-and-set! res nil %))
    (is (= err @res))))

(deftest registering-receive-callbacks-twice-throws
  (let [dval (deferred)]
    (receive dval identity identity)
    (is (thrown?
         Exception
         (receive dval identity identity)))))

(deftest dereferencing-realized-deferred-value
  (let [dval (deferred)]
    (put dval :hello)
    (is (= :hello @dval))))

(deftest dereferencing-pending-deferred-values
  (let [dval (deferred)]
    (future
      (Thread/sleep 50)
      (put dval :hello))
    (is (= :hello @dval @dval)))

  (let [dval (deferred)]
    (future
      (Thread/sleep 50)
      (put dval :hello))
    (receive dval identity identity)
    (is (= :hello @dval @dval))))

(deftest dereferencing-aborted-deferred-value
  (let [dval (deferred)]
    (abort dval (Exception. "BOOM"))
    (is (thrown-with-msg? Exception #"BOOM" @dval))
    (is (thrown-with-msg? Exception #"BOOM" @dval))))

(deftest dereferencing-pending-deferred-values-that-get-aborted
  (let [dval (deferred)]
    (future
      (Thread/sleep 50)
      (abort dval (Exception. "BOOM")))
    (is (thrown-with-msg? Exception #"BOOM" @dval))
    (is (thrown-with-msg? Exception #"BOOM" @dval)))

  (let [dval (deferred)]
    (future
      (Thread/sleep 50)
      (abort dval (Exception. "BOOM")))
    (receive dval identity identity)
    (is (thrown-with-msg? Exception #"BOOM" @dval))
    (is (thrown-with-msg? Exception #"BOOM" @dval))))
