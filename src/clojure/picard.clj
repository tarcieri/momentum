(ns picard
  (:require
   [picard.utils]
   [picard.server]
   [picard.client]
   [picard.pool]
   [picard.proxy]))

(def VERSION        picard.utils/VERSION)
(def SERVER-NAME    picard.utils/SERVER-NAME)
(def start-server   picard.server/start)
(def restart-server picard.server/restart)
(def stop-server    picard.server/stop)
(def request        picard.client/request)
(def HEAD           picard.client/HEAD)
(def GET            picard.client/GET)
(def POST           picard.client/POST)
(def PUT            picard.client/PUT)
(def DELETE         picard.client/DELETE)
(def mk-proxy       picard.proxy/mk-proxy)
(def mk-pool        picard.pool/mk-pool)
(def shutdown-pool  picard.pool/shutdown)
