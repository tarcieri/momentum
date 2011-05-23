(ns picard.core
  (:require
   [picard.server :as srv]
   [picard.client :as clt]
   [picard.proxy  :as prx])
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
      (downstream :respond
                  [200 {"content-type" "text/plain" "content-length" "23"}
                   "Howdy folks\n"])
      (downstream :body "That's all\n")
      (downstream :done nil))))

(defn start-server
  []
  (agent (srv/start my-app)))

(defn -main [& args]
  (println "Welcome to picard!")
  ;; (srv/start my-app)
  (srv/start (prx/mk-proxy))
  (deref (promise)))
