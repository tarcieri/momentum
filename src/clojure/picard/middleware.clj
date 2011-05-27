(ns picard.middleware)

(defn retry
  [app & opts]
  (fn [downstream]
    (let [upstream (app downstream)]
      upstream)))
