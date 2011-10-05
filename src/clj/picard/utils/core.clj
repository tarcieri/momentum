(ns picard.utils.core)

(defn KB
  [kilobytes]
  (* 1024 kilobytes))

(defn MB
  [megabytes]
  (* 1024 1024 megabytes))

(defmacro swap-then!
  [atom swap-fn then-fn]
  `(let [res# (swap! ~atom ~swap-fn)]
     (~then-fn res#)
     res#))
