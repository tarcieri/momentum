(ns picard.middleware)

(defn retry
  [app & opts]
  (fn [downstream request]
    (let [upstream (app downstream request)]
      upstream)))
