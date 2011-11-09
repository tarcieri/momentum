(ns momentum.utils.helpers)

(defmacro build-stack
  "Builds an application stack from downstream to upstream. The last
  argument should be the end application and everything before that
  is middleware."
  ([x] x)
  ([x form] `(~x ~form))
  ([x y & more]
     (let [[a b & rest] (reverse (cons y more))]
       `(build-stack
         ~x ~@(reverse rest)
         (build-stack ~b ~a)))))
