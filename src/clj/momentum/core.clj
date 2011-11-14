(ns momentum.core
  (:require
   [momentum.core.buffer]
   [momentum.core.channel :as channel]
   [momentum.core.deferred]))

;; ==== Buffer helpers
(def buffer?             momentum.core.buffer/buffer?)
(def capacity            momentum.core.buffer/capacity)
(def collapsed?          momentum.core.buffer/collapsed?)
(def direct-buffer       momentum.core.buffer/direct-buffer)
(def dynamic-buffer      momentum.core.buffer/dynamic-buffer)
(def duplicate           momentum.core.buffer/duplicate)
(def flip                momentum.core.buffer/flip)
(def focus               momentum.core.buffer/focus)
(def holds?              momentum.core.buffer/holds?)
(def limit               momentum.core.buffer/limit)
(def position            momentum.core.buffer/position)
(def remaining           momentum.core.buffer/remaining)
(def remaining?          momentum.core.buffer/remaining?)
(def reset               momentum.core.buffer/reset)
(def rewind              momentum.core.buffer/rewind)
(def slice               momentum.core.buffer/slice)
(def to-byte-array       momentum.core.buffer/to-byte-array)
(def to-channel-buffer   momentum.core.buffer/to-channel-buffer)
(def to-string           momentum.core.buffer/to-string)
(def transfer!           momentum.core.buffer/transfer!)
(def transfer            momentum.core.buffer/transfer)
(def wrap                momentum.core.buffer/wrap)
(def write-byte          momentum.core.buffer/write-byte)
(def write-ubyte         momentum.core.buffer/write-ubyte)
(def write-short         momentum.core.buffer/write-short)
(def write-ushort        momentum.core.buffer/write-ushort)
(def write-int           momentum.core.buffer/write-int)
(def write-uint          momentum.core.buffer/write-uint)
(def write-long          momentum.core.buffer/write-long)

;; The protocols
(def DeferredValue    momentum.core.deferred/DeferredValue)
(def DeferredRealizer momentum.core.deferred/DeferredRealizer)

;; Map the macros
(defmacro buffer
  [& args]
  `(momentum.core.buffer/buffer ~@args))

;; ==== Async goodness
(def abort               momentum.core.deferred/abort)
(def batch               momentum.core.deferred/batch)
(def recur*              momentum.core.deferred/recur*)
(def join                momentum.core.deferred/join)
(def deferred            momentum.core.deferred/deferred)
(def pipeline            momentum.core.deferred/pipeline)
(def put                 momentum.core.deferred/put)
(def receive             momentum.core.deferred/receive)
(def channel             channel/channel)
(def close               channel/close)
(def enqueue             channel/enqueue)

(defmacro doasync
  [& args]
  `(momentum.core.deferred/doasync ~@args))

(defmacro async-seq
  [& body]
  `(momentum.core.deferred/async-seq (fn [this#] ~@body)))

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

(defmacro future*
  [& stages]
  `(let [d# (deferred)]
     (future
       (try
         (put d# (do ~@stages))
         (catch Exception e#
           (abort d# e#))))
     d#))
