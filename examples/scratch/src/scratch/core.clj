(ns scratch.core
  (:require
   [picard.client :as client]
   [picard.server :as server])
  (:use [picard.helpers :only [defstream defmiddleware]]))

(defn- minimal-middleware
  [upstream-accept]
  (fn [downstream]
    (let [upstream (upstream-accept
                     (fn [evt val]
                       (println "evt down: " evt val)
                       (downstream evt val)))]
      (fn [evt val]
        (println "evt up:" evt val)
        (upstream evt val)))))

(defn- minimal-macro-middleware
  [upstream-accept]
  (defmiddleware
    [state-atom downstream upstream]
    upstream-accept

    :upstream
    (fn [evt val]
      (println "evt up:" evt val)
      (upstream evt val))

    :downstream
    (fn [evt val]
      (println "evt down:" evt val)
      (downstream evt val))
    ))

(defn- commons-logging-middleware
  [upstream-accept]
  (defmiddleware
    [state-atom downstream upstream]
    upstream-accept

    :upstream
    (fn [evt val]
      (println "evt up:" evt val)
      (upstream evt val))

    :downstream
    (fn [evt val]
      (println "evt down:" evt val)
      (downstream evt val))
    ))

(defn- hello-world-accept
  [dn]
  (fn [evt val]
    (when (= :request evt)
      (dn :response [200 {"content-length" "6"} "Hello\n"]))))

(defn echo-app-accept
  [downstream]
  (defstream

    (request
      [[hdrs body]]
      (downstream :response [200
                             (merge
                              (select-keys hdrs ["content-type" "transfer-encoding" "content-length"])
                              {"connection" "close"})
                             body]))
    (body [body] (downstream :body body))

    (abort [err]
      (println "ZZZZZOMG FIAL")
      (if err (.printStackTrace err)))))

(defonce *picard-server-agent* (agent nil))

(defn start-picard-server
  []
  (send-off
   *picard-server-agent*
   (fn [s]
     (if s (server/stop s))
     (server/start (minimal-macro-middleware echo-app-accept)))))

(defn stop-picard-server
  []
  (send-off *picard-server-agent* (fn [s] (if s (server/stop s)) nil)))

