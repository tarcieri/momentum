(ns picard.scratch)

(defn my-app
  [resp]
  (fn [headers body]
    (resp 200 {"Content-Length" "text/plain"} nil)
    (resp nil nil "Hello world")
    (resp)))

(defn my-middleware
  [app]
  (fn [resp]
    (let [foo "bar"
          upstream (app (fn [& args] (apply resp args)))]
      (fn [&args] (apply upstream args)))))

(middleware
 [upstream downstream]
 ;; state
 (receive [] (upstream 123))
 (respond [] (downstream 456)))

(reify Zomg
  (foo bar)
  (baz []
    lol))

(run (my-middleware app))
