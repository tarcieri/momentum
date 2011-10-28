(ns picard.http.test
  (:use
   clojure.test
   picard.core.buffer)
  (:require
   [picard.http.server :as server]
   [picard.net.test    :as net]))

(def default-hdrs
  {:script-name  ""
   :query-string ""
   :http-version [1 1]
   "host"        "example.org"})

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
        hdrs (merge default-hdrs hdrs {:request-method method :path-info path})]
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

(defn- normalize-response
  [[status hdrs body]]
  [status hdrs (if (keyword? body) body (buffer body))])

(defn- stringify-response
  [[status hdrs body]]
  [status hdrs (if (buffer? body) (to-string body) body)])

(defn response
  [& args]
  (let [[[_ response]]
        (filter
         (fn [[evt val]] (= :response evt))
         (apply received args))]
    response))

(defn response-body
  [& args]
  (let [[_ _ body] (apply response args)]
    body))

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
