(ns ^{:author "Carl Lerche"}
  momentum.core
  (:use momentum.util.namespace)
  (:require
   [momentum.core.async   :as async]
   [momentum.core.buffer  :as buffer]
   [momentum.core.reactor :as reactor]))

;; ==== Buffer helpers

(import-fn #'buffer/buffer?)
(import-fn #'buffer/capacity)
(import-fn #'buffer/clear)
(import-fn #'buffer/collapsed?)
(import-fn #'buffer/direct-buffer)
(import-fn #'buffer/dynamic-buffer)
(import-fn #'buffer/duplicate)
(import-fn #'buffer/flip)
(import-fn #'buffer/focus)
(import-fn #'buffer/holds?)
(import-fn #'buffer/limit)
(import-fn #'buffer/position)
(import-fn #'buffer/remaining)
(import-fn #'buffer/remaining?)
(import-fn #'buffer/retain)
(import-fn #'buffer/rewind)
(import-fn #'buffer/slice)
(import-fn #'buffer/to-byte-array)
(import-fn #'buffer/to-channel-buffer)
(import-fn #'buffer/to-string)
(import-fn #'buffer/transfer!)
(import-fn #'buffer/transfer)
(import-fn #'buffer/transient!)
(import-fn #'buffer/transient?)
(import-fn #'buffer/wrap)
(import-fn #'buffer/write)
(import-fn #'buffer/write-byte)
(import-fn #'buffer/write-ubyte)
(import-fn #'buffer/write-short)
(import-fn #'buffer/write-ushort)
(import-fn #'buffer/write-int)
(import-fn #'buffer/write-uint)
(import-fn #'buffer/write-long)
(import-fn #'buffer/KB)
(import-fn #'buffer/MB)

(import-macro #'buffer/buffer)

;; ==== Async goodness

(import-fn #'async/abort)
(import-fn #'async/aborted?)
(import-fn #'async/async-val)
(import-fn #'async/async-seq?)
(import-fn #'async/batch)
(import-fn #'async/blocking)
(import-fn #'async/channel)
(import-fn #'async/close)
(import-fn #'async/concat*)
(import-fn #'async/enqueue)
(import-fn #'async/interrupt)
(import-fn #'async/join)
(import-fn #'async/put)
(import-fn #'async/recur*)
(import-fn #'async/sink)
(import-fn #'async/splice)
(import-fn #'async/success?)

(import-macro #'async/doasync)
(import-macro #'async/async-seq)
(import-macro #'async/doseq*)
(import-macro #'async/future*)

;; ==== Reactor helpers

(import-fn #'reactor/start-reactors)
(import-fn #'reactor/stop-reactors)
(import-fn #'reactor/schedule-timeout)
(import-fn #'reactor/cancel-timeout)