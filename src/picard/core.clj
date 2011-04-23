(ns picard.core
  (:use [lamina.core])
  (:require
   [picard.server :as srv])
  (:gen-class))

;; (my-app :headers {})
;; (my-app :body "")

;; (downstream :respond [200 {"Content-Type" "text/plain"}])
;; (downstream :body "")
;; (downstream :done nil)

(defn my-app
  [downstream]
  (fn [evt val]
    (when (= evt :headers)
      (println "Got headers: " val))
    (when (= evt :body)
      (println "Got body: " (.toString  val "UTF-8")))
    (when (= evt :done)
      (println "Finishing the request")
      (downstream :respond [200 {"Content-Type" "text/plain"}])
      (downstream :done nil))))

(defn start-server
  []
  (agent (srv/start my-app)))

(defn stop-server
  [server]
  (send server
        (fn [server]
           (when server
             (wait-for-message (server)))
           nil)))

(defn restart-server
  [server]
  (send server
        (fn [server]
          (when server
            (wait-for-message (server)))
          (srv/start my-app))))

(defn -main [& args]
  (println "Welcome to picard!")
  (srv/start my-app)
  (deref (promise)))
