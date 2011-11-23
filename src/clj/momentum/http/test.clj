(ns momentum.http.test
  (:use
   clojure.test
   momentum.core.buffer)
  (:require
   [momentum.http.server :as server]
   [momentum.net.test    :as net]
   [momentum.util.base64 :as base64]
   [momentum.util.digest :as digest]
   [momentum.util.random :as random]))

(defn- ws-hdrs
  [hdrs]
  (merge
   (when (= "websocket" (hdrs "upgrade"))
     {"connection" "upgrade"
      "sec-websocket-key" (base64/encode (random/secure-random 16))
      "sec-websocket-origin" "http://localhost"}) hdrs))

(defn- default-hdrs
  [hdrs]
  (ws-hdrs
   (merge {:script-name  ""    :query-string ""
           :http-version [1 1] "host" "example.org"} hdrs)))

(defn- normalize-request-body
  [o]
  (if (or (nil? o) (keyword? o))
    o (buffer o)))

(defmacro with-app
  [app & stmts]
  `(net/with-app (server/handler ~app {}) ~@stmts))

(defn- request*
  [method path hdrs body]
  (let [conn (net/open)
        hdrs (default-hdrs hdrs)
        hdrs (merge hdrs {:request-method method :path-info path})]
    (conn :request [hdrs (normalize-request-body body)])
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

(def received        net/received)
(def last-connection net/last-connection)
(def closed?         net/closed?)
(def last-request    last-connection)

(defn send-chunks
  [& chunks]
  (doseq [chunk chunks]
    (last-connection :body (buffer chunk))))

(defn- normalize-response
  [[status hdrs body]]
  [status hdrs (if (keyword? body) body (buffer body))])

(defn- stringify-response
  [[status hdrs body]]
  [status hdrs (if (buffer? body) (to-string body) body)])

(defn response
  ([] (response (last-connection)))
  ([conn]
     (second (first (filter (fn [[evt val]] (= :response evt)) conn)))))

(defn response-status
  [& args]
  (first (apply response args)))

(defn response-headers
  [& args]
  (second (apply response args)))

(defn response-body
  [& args]
  (nth (apply response args) 2))

(defn response-body-chunks
  ([] (response-body-chunks (last-connection)))
  ([conn]
     (filter (fn [[evt val]] (= :body evt)) conn)))

(defn response-body-chunks
  [& args]
  (->> (apply received args)
       (filter #(= :body (first %)))
       (map second)))

(defn assert-responded?
  ([msg expected f]
     (assert-responded? msg (net/last-connection) expected f))
  ([msg conn expected f]
     (let [actual (response conn)
           match? (= (normalize-response expected)
                     (normalize-response actual))]

       (f {:type     (if match? :pass :fail)
           :message  msg
           :expected (stringify-response expected)
           :actual   (stringify-response actual)}))))

(defmethod assert-expr 'responded? [msg [_ & args]]
  `(assert-responded? ~msg ~@args #(do-report %)))

(defn assert-received?
  [f msg & expected]
  (assert (even? (count expected)) "Must provide event / value pairs")
  (let [expected (partition 2 expected)
        actual   (take (count expected) (received))]

    (f {:type     (if (= expected actual) :pass :fail)
        :message  msg
        :expected expected
        :actual   actual})))

(defmethod assert-expr 'received? [msg [_ & args]]
  `(assert-received? #(do-report %) ~msg ~@args))
