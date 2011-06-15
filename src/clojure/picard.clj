(ns picard
  (:require
   [picard.utils]
   [picard.server]
   [picard.client]
   [picard.pool]
   [picard.proxy]))

(def VERSION      picard.utils/VERSION)
(def SERVER-NAME  picard.utils/SERVER-NAME)
(def start-server picard.server/start)
(def stop-server  picard.server/stop)
(def request      picard.client/request)
(def mk-proxy     picard.proxy/mk-proxy)
(def mk-pool      picard.pool/mk-pool)
