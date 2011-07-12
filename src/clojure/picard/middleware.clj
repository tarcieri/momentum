(ns picard.middleware
  (:require
   [picard.middleware.body-buffer]
   [picard.middleware.json]
   [picard.middleware.logging]
   [picard.middleware.retry]))

(def body-buffer picard.middleware.body-buffer/body-buffer)
(def json        picard.middleware.json/json)
(def logging     picard.middleware.logging/logging)
(def retry       picard.middleware.retry/retry)
