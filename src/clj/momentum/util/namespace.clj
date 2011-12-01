(ns ^{:author "Zachary Tellman"}
  momentum.util.namespace)

(defmacro import-fn
  "Given a function in another namespace, defines a function with the same name in the
   current namespace.  Argument lists, doc-strings, and original line-numbers are preserved."
  [sym]
  (let [m        (meta (eval sym))
        m        (meta (intern (:ns m) (:name m)))
        n        (symbol (name (:name m))) ;; Work around a strange bug
        arglists (:arglists m)
        doc      (:doc m)]
    ;; TODO: Don't assoc :file and :line if they are nil (which is the
    ;; case for protocols)
    `(do
       (def ~n ~(eval sym))
       (alter-meta!
        ~(list 'var n) assoc
        :doc ~doc
        :arglists '~arglists
        :file ~(:file m)
        :line ~(:line m))
       ~(list 'var n))))

(defmacro import-macro
  "Given a macro in another namespace, defines a macro with the same name in the
   current namespace.  Argument lists, doc-strings, and original line-numbers are preserved."
  [sym]
  (let [sym      (eval sym)
        m        (meta sym)
        m        (meta (intern (:ns m) (:name m)))
        n        (:name m)
        arglists (:arglists m)
        doc      (:doc m)
        args-sym (gensym "args")]
    `(do
       (defmacro ~n
         [~'& ~args-sym]
         (list* ~sym ~args-sym))
       (alter-meta!
        ~(list 'var n) assoc
        :arglists '~arglists
        :doc ~doc
        :file ~(:file m)
        :line ~(:line m))
       ~(list 'var n))))

