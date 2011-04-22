(ns picard.core
  (:use [lamina.core])
  (:require
   [picard.netty :as netty])
  (:gen-class))

;; (my-app :headers {})
;; (my-app :body "")

;; (downstream :respond [200 {"Content-Type" "text/plain"}])
;; (downstream :body "")
;; (downstream :done nil)

(defn my-app
  [downstream]
  (fn [evt val]
    (when (= evt :done)
      (downstream :respond [200 {"Content-Type" "text/plain"}])
      (downstream :done nil))))

(defn start-server
  []
  (agent (netty/start-server my-app)))

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
          (netty/start-server my-app))))

(defn -main [& args]
  (println "Welcome to picard!")
  (netty/start-server my-app)
  (deref (promise)))
