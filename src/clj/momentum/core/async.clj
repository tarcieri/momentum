(ns momentum.core.async
  (:use
   momentum.core.atomic)
  (:import
   [momentum.async
    Async
    AsyncSeq
    AsyncVal
    AsyncTransferQueue
    Join
    Pipeline
    Pipeline$Catcher
    Pipeline$Recur
    Realizer
    Receiver]
   [java.io
    Writer]))

;; ==== Async common ====

(defn put
  [^Realizer realizer val]
  (.put realizer val))

(defn abort
  [^Realizer realizer err]
  (.abort realizer err))

(defn success?
  [^Async val]
  (.isSuccessful val))

(defn aborted?
  [^Async val]
  (.isAborted val))

;; ==== Async value ====

(defn async-val
  []
  (AsyncVal.))

;; ==== Pipeline ====

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

;; ==== Async seq ====

(defmacro async-seq
  [& body]
  `(AsyncSeq. (fn [] ~@body)))

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
    (doasync coll
      (fn [[v & more]]
        (cons v (map* f more))))))

(defmacro doseq*
  [seq-exprs & body]
  (assert (vector? seq-exprs) "a vector for its binding")
  (assert (even? (count seq-exprs)) "an even number of forms in binding vector")
  (let [[binding seq] seq-exprs]
    `(doasync ~seq
       (fn [s#]
         (when-let [[~binding & more#] s#]
           ~@body
           (recur* more#))))))

(defn- select-seq
  [vals]
  (when (seq vals)
    (async-seq
      (doasync (first vals)
        #(cons % (select-seq (next vals)))))))

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

(defmacro future*
  [& body]
  `(let [val# (async-val)]
     (future
       (try
         (put val# (do ~@body))
         (catch Exception e#
           (abort val# e#))))
     val#))

(defmethod print-method AsyncSeq
  [seq ^Writer w]
  (.write w (str seq)))

;; ==== Channels ====

(declare
 toggle-availability)

(deftype Channel [transfer head paused? depth f capacity]
  clojure.lang.Seqable
  (seq [_]
    @head)

  Realizer
  (put [this val]
    (let [ret (.put (.transfer this) val)]
      (when (.f this)
        (toggle-availability this))
      ret))

  (abort [this err]
    (.abort (.transfer this) err))

  clojure.lang.IFn
  (invoke [this v]
    (put this v))

  clojure.lang.Counted
  (count [this]
    (.count (.transfer this))))

(defn- full?
  [ch]
  (>= (count ch) (.capacity ch)))

(defn- toggle-availability
  [ch]
  ;; Atomically increment the depth counter
  (let [depth (swap! (.depth ch) inc)]
    ;; If the current value (after incrementing) is 1, then this
    ;; thread won the race for invoking the fn w/ :pause / :resume
    ;; events. Any losing thread has been tracked by incrementing the
    ;; counter.
    (when (= 1 depth)
      (let [f (.f ch)]
        (loop [paused? @(.paused? ch) depth depth]
          ;; If the current state of the stream does not match that of
          ;; the channel, then an event must be sent downstream
          (let [new-paused? (full? ch)]
            (when-not (= paused? new-paused?)
              (f (if new-paused? :pause :resume) nil))

            ;; Now, the counter can be decremented by the value read
            ;; after the atomic increment since all threads that
            ;; incremented the counter before the swap has been acounted
            ;; for. If the value after the decrement is not 0, then
            ;; other threads have been tracked during the downstream
            ;; function invocation, so the process must be restarted.
            (let [depth (swap! (.depth ch) #(- % depth))]
              (when (< 0 depth)
                (recur new-paused? depth)))))))))

(defn- channel-seq
  [ch]
  (async-seq
    (doasync (.take (.transfer ch))
      (fn [v]
        (when-not (= ::close-channel v)
          (when (.f ch)
            (toggle-availability ch))
          (let [nxt (channel-seq ch)]
            (reset! (.head ch) nxt)
            (cons v nxt)))))))

(defn channel
  ([]  (channel nil 0))
  ([f] (channel f 1))
  ([f capacity]
     (let [qu (AsyncTransferQueue. ::close-channel)
           ch (Channel. qu (atom nil) (atom false) (atom 0) f capacity)]
       (reset! (.head ch) (channel-seq ch))
       ch)))

(defn enqueue
  ([_])
  ([ch & vs]
     (loop [vs vs cont? true]
       (when-let [[v & more] vs]
         (when (and vs cont?)
           (recur more (put ch v)))))))

(defn close
  [ch]
  (.close (.transfer ch)))

(defn- sink-seq
  [coll evts]
  (async-seq
    (doasync (first* (select {:coll coll :evts evts}))
      (fn [[k v]]
        (when v
          (cond
           (= :coll k)
           (cons (first v) (sink-seq (next v) evts))

           (= :pause (first v))
           (doasync (next v)
             (fn [[evt & more]]
               (if (= :pause evt)
                 (recur* more)
                 (sink-seq coll more))))

           :else
           (sink-seq coll (next v))))))))

;; TODO: Don't hardcode this to :body events
(defn sink
  "Writes the contents of the collection to a downstream
  function. Returns a function that accepts :pause, :resume,
  and :abort events"
  [dn coll]
  (let [ch (channel)]
    (doasync (sink-seq coll (seq ch))
      (fn [coll]
        (if-let [[el & more] coll]
          (do
            (dn :body el)
            (recur* more))
          (dn :body nil)))
      ;; Handle exceptions by sending them downstream
      (catch Exception e
        (dn :abort e)))

    ;; Return an upstream function that allows sending pause / resume
    ;; events to the sink
    (fn [evt val]
      (when-not (#{:pause :resume :abort} evt)
        (throw (IllegalArgumentException. (format "Invalid events: %s" evt))))
      (if (= :abort evt)
        (abort ch val)
        (put ch evt)))))

