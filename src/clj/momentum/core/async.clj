(ns ^{:author "Carl Lerche"
      :doc
      "The purpose of this namespace is to provide a collection of
       asynchronous primitives and functions to operate on those
       primitives.

       ### Asynchronous values

       The core primitive is AsyncVal. This is basically just a
       future (except...), but the name future is already used in
       clojure.core, so we use a different name to avoid conflicts. A
       producer is responsible for dealing with raw asynchronous
       operations and exposing them as AsyncVals. A consumer interacts
       with the producer, and interacts with the asynchronous
       operations through AsyncVals.

       An AsyncVal represents a computation that might still be in
       progress, and which will eventually succeed or fail. A producer
       realizes the AsyncVal when the raw asynchronous operations it
       manages have information the producer wants to pass along to
       the consumer.

       For example, an HTTP request function would return an AsyncVal
       representing the response before it receives the response. Once
       the HTTP client receives the response, it will realize the
       AsyncVal, which will invoke any registered realization
       callbacks. If something goes wrong (for example, the connection
       was closed prematurely), then the producer would realize the
       AsyncVal with the exception.

       If you have an AsyncVal, you will normally receive its realized
       value by registering a callback on it. You can also dereference
       the AsyncVal immediately, which will block the current thread
       under the producer realizes the AsyncVal. You should never
       dereference an AsyncVal inside an event loop since doing so
       will freeze the entire system.

       ### doasync

       You register callbacks on an AsyncVal using the doasync
       macro. A full example might look something like:

           (doasync (http/GET \"http://www.google.com\")
             ;; Function invoked when the asynchronous value returned by
             ;; http/GET is realized.
             (fn [[status hdrs body]]
               (println \"GOT: \" [status hdrs body])))

       The doasync macro itself returns an AsyncVal representing the
       return value from the callback, making doasync composable.

       Should the producer (the HTTP client in the above example)
       encounter a failure, the AsyncVal can be aborted with an
       exception representing the failure. doasync allows these
       asynchronous exceptions to be handled as well in a similar
       fashion as clojure’s try / catch.

       For example:

           (doasync (http/GET \"http://www.some-invalid-host.com/\")
             (fn [resp]) ;; Will not get invoked
             (catch Exception e
               (println \"Encountered an exception: \" e)))

       If an exception is successfully caught, the AsyncVal
       representing the doasync will be realized with the catch
       clause’s return value. Exceptions that are not handled will
       cause the AsyncVal to be aborted with exception. This semantic
       allows exceptions to bubble up asynchronously.

       Additionally, doasync can handle any clojure type or java
       object, in which case, the callback gets invoked immediately.

       ### Joining asynchronous values

       The join function takes an arbitrary number of both
       asynchronous values and regular types / objects and returns an
       AsyncVal that becomes realized when all of the arguments become
       realized. The realized arguments are then applied to the
       callback function.

       For example:

           (doasync (join (http/GET \"http://www.google.com/\")
                          (http/GET \"http://www.bing.com/\"))
             (fn [google-response bing-response]
               ;; Do something with the responses
               ))

       In the event that one of the arguments becomes aborted, the
       combined AsyncVal will also become aborted with the same
       exception.

       ### AsyncSeq

       AsyncSeq is an AsyncVal that is always realized with a clojure
       sequence or nil. It also implements the clojure sequence
       protocol, however calling first, more, or next on it will throw
       an exception if it has not been realized yet. AsyncSeqs can
       used with doasync just the same as AsyncVals can.

       For example:

           (doasync my-async-seq
             (fn [[val & more :as realized]]
               ;; more is another async-seq
               (when realized
                 (println \"GOT: \" val)
                 (recur* more))))

       The recur* function allows asynchronous recursion. It must be
       used in a tail position. The recur* function takes N arguments
       and joins them as explained above. Once the join becomes
       realized, it is applied to the last invoked function.

       Just like with AsyncVals, an AsyncSeq might also face a failure
       scenario and become aborted with an exception. These exceptions
       may be handled in the same way as the previous catch example

       ### Composability

       The above primitives are enough to build up some powerful
       asynchronous abstractions. This namespace contains a number of
       these abstractions. For example, map* returns an asynchronous
       sequence consisting of applying a function to the elements of
       another asynchronous sequence. The first* function returns an
       asynchronous value representing the head of a given sequence
       once it becomes realized. In all of these cases, exception
       handling behaves as expected."}
  momentum.core.async
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
  "Realize the asynchronous value with the supplied value. Returns
  true is successful. Returns false otherwise."
  [^Realizer async-val val]
  (.put async-val val))

(defn abort
  "Abort the asynchronous value with the supplied exception. Returns
  true if successful. Returns false otherwise."
  [^Realizer async-val err]
  (.abort async-val err))

(defn success?
  "Returns true if the asynchronous value has been realized
  successfully."
  [^Async async-val]
  (.isSuccessful async-val))

(defn aborted?
  "Returns true if the asynchronous value has been aborted."
  [^Async async-val]
  (.isAborted async-val))

;; ==== Async value ====

(defn async-val
  "Returns a new unrealized asynchronous value. Dereferencing will
  cause the current thread to block until the asynchronous value is
  realized."
  []
  (AsyncVal.))

;; ==== Pipeline ====

(defn pipeline
  [stages catchers finalizer]
  (Pipeline. (reverse stages) catchers finalizer))

(defn join
  "Returns an asynchronous value representing the realization of the
  supplied arguments. The returned asynchronous value will be realized
  with the realized values of the supplied arguments in the same order
  or, if any of the suppplied arguments become aborted, it will be
  aborted with the same exception."
  [& args]
  (Join. args))

(defn recur*
  "Accepts an aribtrary number of arguments, passing them to
  join. Once the joined asynchronous value is realized, the current
  callback function will be reinvoked with the joined realized values
  from the supplied arguments. Must be called from the tail position
  of a doasync callback."
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
  "Takes a body of expressiosn that returns an asynchronous value,
  ISeq, or nil, and yields a Sequable asynchronous value that will
  invoke the body only the first time a callback is registered or it
  is dereferenced, and will cache the result and return it on
  subsequent calls to seq."
  [& body]
  `(AsyncSeq. (fn [] ~@body)))

(defn async-seq?
  "Returns true if x is an async-seq."
  [x]
  (instance? AsyncSeq x))

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
  "Alpha - subject to change

  Returns an async value that is realized with the given collection
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
  "Returns an asynchronous sequence consisting of the result of recursively
  applying f to the set of first items of each coll once they become
  realized. Function f should accept the number of colls arguments."
  ([f coll]
     (async-seq
       (doasync coll
         (fn [[v & more]]
           (cons v (map* f more))))))
  ([f c1 & colls]
     (throw (Exception. "Not implemented yet."))))

(defmacro doseq*
  "Repeatedly executes body (presumably for side-effects) with
  bindings as they are realized and filtering as provided by
  \"for\". Does not retain the head of the sequence. Returns an
  asynchronous value that will be realized with nil once all of the
  items have been handled."
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
  "Takes a body of expressions and invoke it in another
  thread. Returns an asynchronous value that will be realized with the
  result once the computation completes."
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
  "Returns a new channel. Calling seq with a channel returns an
  asynchronous sequence of the values that are put into the channel."
  ([]  (channel nil 0))
  ([f] (channel f 1))
  ([f capacity]
     (let [qu (AsyncTransferQueue. ::close-channel)
           ch (Channel. qu (atom nil) (atom false) (atom 0) f capacity)]
       (reset! (.head ch) (channel-seq ch))
       ch)))

(defn enqueue
  "Put multiple values into a channel."
  ([_])
  ([ch & vs]
     (loop [vs vs cont? true]
       (when-let [[v & more] vs]
         (when (and vs cont?)
           (recur more (put ch v)))))))

(defn close
  "Close a channel. Closing a channel causes any associated
  asynchronous sequences to terminate."
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

