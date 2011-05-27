(ns picard.api)

(defn response-status  [[status]]    status)
(defn response-headers [[_ headers]] headers)
(defn response-body    [[_ _ body]]  body)

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
