(ns momentum.core
  (:require
   [momentum.core.buffer   :as buffer]
   [momentum.core.deferred :as deferred]))

;; ==== Buffer helpers
(def buffer?             buffer/buffer?)
(def capacity            buffer/capacity)
(def collapsed?          buffer/collapsed?)
(def direct-buffer       buffer/direct-buffer)
(def dynamic-buffer      buffer/dynamic-buffer)
(def duplicate           buffer/duplicate)
(def flip                buffer/flip)
(def focus               buffer/focus)
(def holds?              buffer/holds?)
(def limit               buffer/limit)
(def position            buffer/position)
(def remaining           buffer/remaining)
(def remaining?          buffer/remaining?)
(def reset               buffer/reset)
(def rewind              buffer/rewind)
(def slice               buffer/slice)
(def to-byte-array       buffer/to-byte-array)
(def to-channel-buffer   buffer/to-channel-buffer)
(def to-string           buffer/to-string)
(def transfer!           buffer/transfer!)
(def transfer            buffer/transfer)
(def wrap                buffer/wrap)
(def write-byte          buffer/write-byte)
(def write-ubyte         buffer/write-ubyte)
(def write-short         buffer/write-short)
(def write-ushort        buffer/write-ushort)
(def write-int           buffer/write-int)
(def write-uint          buffer/write-uint)
(def write-long          buffer/write-long)

;; Map the macros
(defmacro buffer
  [& args]
  `(buffer/buffer ~@args))

;; ==== Async goodness
(def abort               deferred/abort)
(def aborted?            deferred/aborted?)
(def batch               deferred/batch)
(def blocking            deferred/blocking)
(def recur*              deferred/recur*)
(def join                deferred/join)
(def select              deferred/select)
(def async-val           deferred/async-val)
(def async-seq?          deferred/async-seq?)
(def pipeline            deferred/pipeline)
(def put                 deferred/put)
(def success?            deferred/success?)
(def channel             deferred/channel)
(def close               deferred/close)
(def enqueue             deferred/enqueue)
(def sink                deferred/sink)

(defmacro doasync
  [& args]
  `(deferred/doasync ~@args))

(defmacro async-seq
  [& body]
  `(deferred/async-seq (fn [] ~@body)))

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
  [& body]
  `(let [val# (async-val)]
     (future
       (try
         (put val# (do ~@body))
         (catch Exception e#
           (abort val# e#))))
     val#))
