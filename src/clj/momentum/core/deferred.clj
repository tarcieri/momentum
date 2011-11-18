(ns momentum.core.deferred
  (:import
   [momentum.async
    Async
    AsyncSeq
    AsyncVal
    Join
    Pipeline
    Pipeline$Catcher
    Pipeline$Recur
    Receiver]))

(defprotocol DeferredValue
  (receive   [_ success error]))

(extend-protocol DeferredValue
  Async
  (receive [val success error]
    (doto val
      (.receive
       (reify Receiver
         (success [_ val] (success val))
         (error   [_ err] (error err))))))

  Object
  (receive [o success _]
    (success o)
    o)

  nil
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

(defn async-val
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
  (Join. args))

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
  ([f] (AsyncSeq. f)))

(defn batch
  "Returns an async value that is realized with the given collection
  when all (or n if supplied) elements of the collection have been
  realized."
  ([coll] (batch Integer/MAX_VALUE coll))
  ([n coll]
     (if (= 0 n)
       coll
       (doasync (join (dec n) coll)
         (fn [n realized]
           (if (and realized (< 0 n))
             (recur* (join (dec n) (next realized)))
             coll))))))

(defn map*
  [f coll]
  (async-seq
    (fn []
      (doasync coll
        (fn [[v & more]]
          (cons v (map* f more)))))))
