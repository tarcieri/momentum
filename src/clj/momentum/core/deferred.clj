(ns momentum.core.deferred
  (:use
   momentum.core.atomic)
  (:import
   [momentum.async
    Async
    AsyncSeq
    AsyncVal
    Join
    Pipeline
    Pipeline$Catcher
    Pipeline$Recur
    Receiver]
   [java.io
    Writer]))

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

(defn success?
  [^Async val]
  (.isSuccessful val))

(defn aborted?
  [^Async val]
  (.isAborted val))

;; ==== Pipeline stuff

(defn pipeline
  [stages catchers finalizer]
  (Pipeline. (reverse stages) catchers finalizer))

(defn join
  [& args]
  (Join. args))

(defn recur*
  ([]                (Pipeline$Recur. nil))
  ([v1]              (Pipeline$Recur. v1))
  ([v1 v2]           (Pipeline$Recur. (join v1 v2)))
  ([v1 v2 v3]        (Pipeline$Recur. (join v1 v2 v3)))
  ([v1 v2 v3 & args] (Pipeline$Recur. (apply join v1 v2 v3 args))))

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

(defn- async-vals
  "A lazy sequence of async-vals"
  []
  (lazy-seq (cons (async-val) (async-vals))))

(defn async-seq
  ([f] (AsyncSeq. f)))

(defn async-seq?
  [o]
  (instance? AsyncSeq o))

(defn- realize-coll
  [coll ms]
  (if (async-seq? coll)
    (deref coll ms nil)
    coll))

(defn blocking
  "Returns a lazy sequence consisting of the items in the passed
  collection If the sequence is an async sequence, then the current
  thread will wait at most ms milliseconds (or indefinitely if no
  timeout value passed) for the async sequence to realize."
  ([coll] (blocking coll -1))
  ([coll ms]
     (lazy-seq
      (let [coll (realize-coll coll ms)]
        (when coll
          (cons (first coll) (blocking (next coll))))))))

(defn first*
  "Returns an async value representing the first item in the
  collection once it becomes realized."
  [async-seq]
  (doasync async-seq
    #(first %)))

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

(defn- select-seq
  [vals]
  (when (seq vals)
    (async-seq
      (fn []
        (doasync (first vals)
          #(cons % (select-seq (next vals))))))))

(defn- map-entry
  [k v]
  (clojure.lang.MapEntry. k v))

(defn- watch-coll
  [coll head]
  (let [entry? (map? coll)]
    (doseq [el coll]
      (doasync (if entry? (val el) el)
        (fn [v]
          (when-let [async-val (atomic-pop! head)]
            (if entry?
              (put async-val (map-entry (key el) v))
              (put async-val v))))
        (catch Exception e
          (when-let [async-val (atomic-pop! head)]
            (abort async-val e)))))))

(defn select
  "Returns an async seq representing the values of the passed
  collection in the order that they are materialized."
  [coll]
  (let [results (take (count coll) (async-vals))]
    (watch-coll coll (atom results))
    (select-seq results)))

(defn splice
  [map]
  "Returns an async seq that consists of map entries of the values of
  all of the seqs passed in as they materialize and the key
  referencing the seq."
  (async-seq
    (doasync (first* (select map))
      (fn [[k seq]]
        (if-let [[v & more] seq]
          (cons (map-entry k v) (splice (assoc map k more)))
          (splice (dissoc map k)))))))

(defmethod print-method AsyncSeq
  [seq ^Writer w]
  (.write w (str seq)))
