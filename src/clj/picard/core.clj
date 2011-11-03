(ns picard.core
  (:require
   picard.core.buffer
   picard.core.deferred))

;; ==== Buffer helpers
(def buffer?             picard.core.buffer/buffer?)
(def capacity            picard.core.buffer/capacity)
(def collapsed?          picard.core.buffer/collapsed?)
(def direct-buffer       picard.core.buffer/direct-buffer)
(def dynamic-buffer      picard.core.buffer/dynamic-buffer)
(def duplicate           picard.core.buffer/duplicate)
(def flip                picard.core.buffer/flip)
(def focus               picard.core.buffer/focus)
(def holds?              picard.core.buffer/holds?)
(def limit               picard.core.buffer/limit)
(def position            picard.core.buffer/position)
(def remaining           picard.core.buffer/remaining)
(def remaining?          picard.core.buffer/remaining?)
(def reset               picard.core.buffer/reset)
(def rewind              picard.core.buffer/rewind)
(def slice               picard.core.buffer/slice)
(def to-byte-array       picard.core.buffer/to-byte-array)
(def to-channel-buffer   picard.core.buffer/to-channel-buffer)
(def to-string           picard.core.buffer/to-string)
(def transfer!           picard.core.buffer/transfer!)
(def transfer            picard.core.buffer/transfer)
(def wrap                picard.core.buffer/wrap)
(def write-byte          picard.core.buffer/write-byte)
(def write-ubyte         picard.core.buffer/write-ubyte)
(def write-short         picard.core.buffer/write-short)
(def write-ushort        picard.core.buffer/write-ushort)
(def write-int           picard.core.buffer/write-int)
(def write-uint          picard.core.buffer/write-uint)
(def write-long          picard.core.buffer/write-long)

;; The protocols
(def DeferredValue    picard.core.deferred/DeferredValue)
(def DeferredRealizer picard.core.deferred/DeferredRealizer)

;; Map the macros
(defmacro buffer
  [& args]
  `(picard.core.buffer/buffer ~@args))

;; ==== Async goodness
(def abort               picard.core.deferred/abort)
(def arecur              picard.core.deferred/arecur)
(def blocking-channel    picard.core.deferred/blocking-channel)
(def channel             picard.core.deferred/channel)
(def close               picard.core.deferred/close)
(def deferred            picard.core.deferred/deferred)
(def enqueue             picard.core.deferred/enqueue)
(def pipeline            picard.core.deferred/pipeline)
(def put                 picard.core.deferred/put)
(def put-last            picard.core.deferred/put-last)
(def receive             picard.core.deferred/receive)

(defmacro doasync
  [& args]
  `(picard.core.deferred/doasync ~@args))

(defmacro doseq*
  [seq-exprs & body]
  (assert (vector? seq-exprs) "a vector for its binding")
  (assert (even? (count seq-exprs)) "an even number of forms in binding vector")
  (let [[binding seq] seq-exprs]
    `(doasync ~seq
       (fn [[~binding & more#]]
         ~@body
         (when more#
           (arecur more#))))))

(defmacro future*
  [& stages]
  `(let [d# (deferred)]
     (future
       (try
         (put d# (do ~@stages))
         (catch Exception e#
           (abort d# e#))))
     d#))
