(ns momentum.test.util.gate
  (:use
   clojure.test
   momentum.util.gate))

(def event-seq
  ((fn step [i]
     (lazy-seq
      (cons [:count i] (step (inc i)))))
   0))

(defn- window
  [coll i n]
  (take n (drop i coll)))

(defn- init-tracked-gate
  []
  (let [received (atom [])
        gated    (init (fn [evt val] (swap! received #(conj % [evt val]))))]
    [gated received]))

(deftest basic-event-proxying
  (let [[gated received] (init-tracked-gate)]

    (doseq [[evt val] (take 20 event-seq)]
      (gated evt val))

    (is (= @received (take 20 event-seq)))))

(deftest basic-open-close-gate
  (let [[gated received] (init-tracked-gate)]
    (doseq [[evt val] (take 10 event-seq)]
      (gated evt val))

    (close! gated)

    (doseq [[evt val] (window event-seq 10 10)]
      (gated evt val))

    (is (= @received (take 10 event-seq)))

    (open! gated)

    (is (= @received (take 20 event-seq)))))

(deftest handles-open-and-close-being-called-concurrently
  (let [[gated received] (init-tracked-gate)]
    (dotimes [_ 30]
      (future
        (dotimes [_ 1000]
          (Thread/sleep 1)
          (if (> (rand 2) 1)
            (open! gated)
            (close! gated)))))

    @(future
       (doseq [[evt val] (take 1000 event-seq)]
         (Thread/sleep 1)
         (gated evt val)))

    ;; Wait a little bit
    (Thread/sleep 50)

    ;; Ensure the gate is open
    (open! gated)

    (let [actual @received]
      (dotimes [i 1000]
        (when-not (= (second (get actual i)) i)
          (println "Count @ " i " == " (get actual i))))
      (is (= actual (take 1000 event-seq))))))
