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

(def *counter-atom* 0)

(defn- hello-world-accept
  [dn]
  (fn [evt val]
    (let [counter (swap! inc *counter-atom*)
          response-text (str "hello " counter "\n")]
     (when (= :request evt)
       (dn :response [200 {"content-length" (str (count response-text))} response-text])))))

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
      (if err (.printStackTrace err)))))

(defonce *picard-server-agent* (agent nil))

(defn start-picard-server
  []
  (send-off
   *picard-server-agent*
   (fn [s]
     (when s
       (println "about to stop server")
       (server/stop s)
       (println "did stop server"))
     (server/start hello-world-accept))))

(defn stop-picard-server
  []
  (send-off *picard-server-agent* (fn [s] (if s (server/stop s)) nil)))

