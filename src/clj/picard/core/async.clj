(ns picard.core.async
  (:use picard.core.deferred))

(defn- stage
  [last curr next f]
  (receive
   curr
   (fn [val]
     (try
       (receive
        (f val)
        (fn [ret]
          (put next ret))
        #(abort last %))
       (catch Exception err
         (abort last err))))
   #(abort last %)))

(defn pipeline
  [seed & stage-fns]
  (let [last (deferred)]
    (loop [curr seed [f & more] stage-fns]
      (if more
        (let [next (deferred)]
          (stage last curr next f)
          (recur next more))
        (stage last curr last f)))
    last))

(defn- catch?
  [clause]
  (and (seq? clause) (= 'catch (first clause))))

(defn- finally?
  [clause]
  (and (seq? clause) (= 'finally (first clause))))

(defn- partition-clauses
  [clauses]
  (reduce
   (fn [[stages catches finally] clause]
     (cond
      (and (catch? clause) (not finally))
      [stages (conj catches clause) finally]

      (and (finally? clause) (not finally))
      [stages catches clause]

      (or (catch? clause) (finally? clause) (first catches) finally)
      (throw (IllegalArgumentException. (str "malformed pipeline statement: " clause)))

      :else
      [(conj stages clause) catches finally]))
   [[] [] nil] clauses))

(defn- finally-handler
  [ret after stmts]
  (if-not stmts
    after
    `(try
       ~@stmts
       ~after
       (catch Exception err#
         (abort ~ret err#)))))

(defn- success-handler
  [ret [_ & finally]]
  (let [val (gensym "value")]
    `(fn [~val]
       ~(finally-handler ret `(put ~ret ~val) finally))))

(defn- catch-handler
  [ret err finally else [_ klass binding & stmts]]
  (let [val (gensym "value")]
    `(if (instance? ~klass ~err)
       (let [~val (let [~binding ~err] ~@stmts)]
         ~(finally-handler ret `(put ~ret ~val) finally))
       ~else)))

(defn- err-handler
  [ret catches [_ & finally]]
  (let [err (gensym "error")]
    (if (empty? catches)
      `(fn [~err]
         ~(finally-handler ret `(abort ~ret ~err) finally))

      `(fn [~err]
         (try
           ~(reduce
              (partial catch-handler ret err finally)
              (finally-handler ret `(abort ~ret ~err) finally)
              catches)
           (catch Exception ~err
             ~(finally-handler ret `(abort ~ret ~err) finally)))))))

(defmacro doasync
  [seed & clauses]
  (let [[stages catches finally] (partition-clauses clauses)
        ret (gensym "return-value")]
    (if (and (empty? catches) (empty? finally))
      ;; If there are no catches and no finally clause, then there is
      ;; no need for the extra deferred value
      `(pipeline ~seed ~@stages)
      `(let [~ret (deferred)]
         (receive
          (pipeline ~seed ~@stages)
          ~(success-handler ret finally)
          ~(err-handler ret catches finally))
         ~ret))))
