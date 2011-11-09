(ns momentum.utils.core)

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

(defmacro swap-assoc!
  [atom & args]
  `(swap! ~atom (fn [val#] (assoc val# ~@args))))

(defmacro defstream
  [& handlers]
  (let [evt (gensym) val (gensym)]
    `(fn [~evt ~val]
       ~(reduce
         (fn [else [evt* bindings & stmts]]
           (if (= 'else evt*)
             `(let [~bindings [~evt ~val]] ~@stmts)
             `(if (= ~(keyword evt*) ~evt)
                (let [~bindings [~val]] ~@stmts)
                ~else)))
         nil (reverse handlers))
       true)))
