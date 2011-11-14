(ns momentum.test.core.channel
  (:use
   clojure.test
   momentum.core))

(deftest single-value-interactions
  (let [ch (channel)
        ls (seq ch)]
    (put ch :hello)
    (let [[head & tail] ls]
      (is (= :hello head))
      (is (not (realized? tail)))
      (is (identical? tail (seq tail)))

      (close ch)
      (is (realized? tail))
      (is (nil? (seq tail)))
      (is (nil? @(doasync tail)))))

  (let [ch (channel)]
    (put ch :hello)
    (let [[head & tail] (seq ch)]
      (is (= :hello head))
      (is (not (realized? tail)))
      (is (identical? tail (seq tail)))

      (close ch)
      (is (realized? tail))
      (is (nil? (seq tail)))
      (is (nil? @(doasync tail)))))

  (let [ch  (channel)
        ret (doasync (batch (seq ch)))]
    (put ch :hello)
    (close ch)
    (is (= [:hello] @ret))))
