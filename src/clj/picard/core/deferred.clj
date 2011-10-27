(ns picard.core.deferred
  (:import
   [picard.core
    Channel
    Deferred
    DeferredSeq
    DeferredReceiver]))

(defprotocol DeferredValue
  (received? [_])
  (received  [_])
  (receive   [_ success error]))

(extend-protocol DeferredValue
  Deferred
  (received? [val]
    (.isRealized val))
  (received [val]
    (deref val 0 nil))
  (receive [val success error]
    (doto val
      (.receive
       (reify DeferredReceiver
         (success [_ val] (success val))
         (error   [_ err] (error err))))))

  DeferredSeq
  (received? [seq]
    (.isRealized seq))
  (received [seq]
    seq)
  (receive [seq success error]
    (doto seq
      (.receive
       (reify DeferredReceiver
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
  Channel
  (put   [ch val] (doto ch (.put val)))
  (abort [ch err] (doto ch (.abort err)))

  Deferred
  (put [dval val]   (doto dval (.put val)))
  (abort [dval err] (doto dval (.abort err))))

(defn deferred
  []
  (Deferred.))

(defn channel
  []
  (Channel.))

(defn blocking-channel
  ([]   (Channel. -1))
  ([ms] (Channel. ms)))

(defn enqueue
  ([_])
  ([ch & vs]
     (loop [[v & vs] vs]
       (let [ret (.put ch v)]
         (if vs
           (recur vs)
           ret)))))

(defn close
  [ch]
  (.close ch))

(defn put-last
  [ch v]
  (doto ch (.putLast v)))

;; ==== Pipeline stuff

(deftype Recur [val])

(defn- stage
  [last curr next f]
  (receive
   curr
   (fn cycle [val]
     (try
       (loop [ret (f val)]
         (cond
          (instance? Recur ret)
          (let [ret (.val ret)]
            (if (received? ret)
              (recur (f (received ret)))
              (receive ret cycle #(abort last %))))

          :else
          (receive ret #(put next %) #(abort last %))))
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

(defn arecur
  ([]    (arecur nil))
  ([val] (Recur. val)))

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
