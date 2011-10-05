(ns picard.http.test
  (:require
   [picard.http.server :as server]
   [picard.net.test    :as net]))

(def default-hdrs
  {:script-name  ""
   :query-string ""
   :http-version [1 1]
   "host"        "example.org"})

(defmacro with-app
  [app & stmts]
  `(net/with-app (server/handler ~app {}) ~@stmts))

(defn- request*
  [method path hdrs body]
  (let [conn (net/open)
        hdrs (merge default-hdrs hdrs {:request-method method :path-info path})]
    (conn :request [hdrs body])
    conn))

(defn request
  ([method path]
     (request* method path {} nil))
  ([method path hdrs-or-body]
     (if (map? hdrs-or-body)
       (request* method path hdrs-or-body nil)
       (request* method path {} hdrs-or-body)))
  ([method path hdrs body]
     (request* method path hdrs body)))

(defn HEAD   [& args] (apply request "HEAD"   args))
(defn GET    [& args] (apply request "GET"    args))
(defn POST   [& args] (apply request "POST"   args))
(defn PUT    [& args] (apply request "PUT"    args))
(defn DELETE [& args] (apply request "DELETE" args))

(def received net/received)
