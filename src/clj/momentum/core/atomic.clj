(ns momentum.core.atomic)

(defn get-and-set!
  "Atomically sets to the given value and returns the old value."
  [atom new-val]
  (loop [val @atom]
    (if (compare-and-set! atom val new-val)
      val (recur @atom))))

(defn get-and-swap!
  [atom f & args]
  (loop [val @atom]
    (let [new-val (apply f val args)]
      (if (compare-and-set! atom val new-val)
        val (recur @atom)))))

(defn atomic-pop!
  "Atomically pop an element off of a stack. The stack should be
  represented as an atom that references a seq."
  [atom]
  (loop [seq @atom]
    (when seq
      (if (compare-and-set! atom seq (next seq))
        (first seq)
        (recur @atom)))))

(defn atomic-push!
  "Atomically push an element onto a stack. The stack should be
  represented as an atom that references a seq."
  [atom val]
  (loop [seq @atom]
    (let [new-head (cons val seq)]
      (if (compare-and-set! atom seq new-head)
        new-head (recur @atom)))))
