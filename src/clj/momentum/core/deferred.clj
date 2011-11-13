(ns momentum.core.deferred
  (:import
   [momentum.core
    AsyncSeq
    AsyncVal
    DeferredSeq
    Pipeline
    Pipeline$Catcher
    Pipeline$Recur
    Receiver]))

(defprotocol DeferredValue
  (received? [_])
  (received  [_])
  (receive   [_ success error]))

(extend-protocol DeferredValue
  AsyncVal
  (received? [val]
    (.isRealized val))
  (received [val]
    (deref val 0 nil))
  (receive [val success error]
    (doto val
      (.receive
       (reify Receiver
         (success [_ val] (success val))
         (error   [_ err] (error err))))))

  AsyncSeq
  (received? [seq]
    (.isRealized seq))
  (received [seq]
    seq)
  (receive [seq success error]
    (doto seq
      (.receive
       (reify Receiver
         (success [_ val] (success val))
         (error   [_ err] (error err))))))

  Pipeline
  (received? [pipeline]
    (.isRealized pipeline))
  (received [pipeline]
    (deref pipeline 0 nil))
  (receive [val success error]
    (doto val
      (.receive
       (reify Receiver
         (success [_ val] (success val))
         (error   [_ err] (error err))))))

  Object
  (received? [o] true)
  (received  [o] o)
  (receive [o success _]
    (success o)
    o)

  nil
  (received? [_] true)
  (received  [_])
  (receive [_ success _]
    (success nil)
    nil))

(defprotocol DeferredRealizer
  (put [_ v])
  (abort [_ err]))

(extend-protocol DeferredRealizer
  AsyncVal
  (put [dval val]   (.put dval val))
  (abort [dval err] (.abort dval err))

  Pipeline
  (put   [pipeline val] (.put pipeline val))
  (abort [pipeline err] (.abort pipeline err)))

(defn deferred
  []
  (AsyncVal.))

;; ==== Pipeline stuff

(defn pipeline
  [stages catchers finalizer]
  (Pipeline. (reverse stages) catchers finalizer))

(defn recur*
  ([]    (Pipeline$Recur. nil))
  ([val] (Pipeline$Recur. val)))

(defn join
  [& args]
  )

;; ==== Async macro

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

(defn- to-catcher
  [[ _ k b & stmts]]
  `(Pipeline$Catcher. ~k (fn [~b] ~@stmts)))

(defn- to-finally
  [[_ & stmts]]
  `(fn [] ~@stmts))

(defmacro doasync
  [seed & clauses]
  (let [[stages catches finally] (partition-clauses clauses)]
    `(doto (pipeline [~@stages] [~@(map to-catcher catches)] ~(to-finally finally))
       (put ~seed))))

(defn async-seq
  ([f]    (AsyncSeq. f))
  ([ms f] (AsyncSeq. f ms)))

(defn batch
  "Returns a deferred value that is realized with the given collection
  when all (or n if supplied) elements of the collection have been
  realized."
  ([coll]   (throw (Exception. "Not implemented")))
  ([n coll] (throw (Exception. "Not implemented"))))

(defn map*
  [f coll]
  (async-seq
    (fn []
      (doasync coll
        (fn [[v & more]]
          (cons v (map* f more)))))))
