(ns momentum.core
  (:require
   [momentum.core.buffer :as buffer]
   [momentum.core.async  :as async]))

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
(def abort               async/abort)
(def aborted?            async/aborted?)
(def batch               async/batch)
(def blocking            async/blocking)
(def recur*              async/recur*)
(def join                async/join)
(def select              async/select)
(def async-val           async/async-val)
(def async-seq?          async/async-seq?)
(def pipeline            async/pipeline)
(def put                 async/put)
(def success?            async/success?)
(def channel             async/channel)
(def close               async/close)
(def enqueue             async/enqueue)
(def sink                async/sink)

(defmacro doasync
  [& args]
  `(async/doasync ~@args))

(defmacro async-seq
  [& body]
  `(async/async-seq (fn [] ~@body)))

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
