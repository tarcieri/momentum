(ns picard.api)

(defmacro defupstream
  [& handlers]
  (let [evt (gensym) val (gensym)]
    `(fn [~evt ~val]
       ~(reduce
         (fn [else [evt* bindings & stmts]]
           (if (= :else evt*)
             `(let [~bindings [~evt ~val]] ~@stmts)
             `(if (= ~(keyword evt*) ~evt)
                (let [~bindings [~val]] ~@stmts)
                ~else)))
         nil (reverse handlers))
       true)))
