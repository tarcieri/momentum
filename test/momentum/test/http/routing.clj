(ns momentum.test.http.routing
  (:use
   clojure.test
   momentum.core
   momentum.http.test
   momentum.http.routing))

(def params-received (atom nil))
(def path-info       (atom nil))

(defn- reset-params-atom [f]
  (reset! params-received nil)
  (reset! path-info       nil)
  (f))

(def not-found-response
  [404 {"content-length" "9" :http-version [1 1]} (buffer "Not found")])

(def hello-response
  [200 {"content-length" "5" :http-version [1 1]} (buffer "Hello")])

(defn- params
  [& params]
  [204 {:params (apply hash-map params)} nil])

;; (defn- path-info
;;   [script-name path-info]
;;   [204 {:script-name script-name :path-info path-info} nil])

(defn- hello-world
  [dn _]
  (fn [evt val]
    (when (= :request evt)
      (dn :response hello-response))))

(defn- echo-params
  [dn _]
  (fn [evt val]
    (when (= :request evt)
      (reset! params-received (-> val first :params))
      (dn :response [204 {} nil]))))

(defn- echo-path
  [dn _]
  (fn [evt val]
    (when (= :request evt)
      (reset! path-info (select-keys (first val) [:path-info :script-name]))
      (dn :response [204 {} nil]))))

(def fail-response
  [200 {"content-length" "4" :http-version [1 1]} (buffer "fail")])

(defn- fail
  [dn _]
  (fn [evt val]
    (when (= :request evt)
      (dn :response fail-response))))

(deftest single-route-router
  (with-endpoint
    (routing
     (match "/" hello-world))

    (GET "/")
    (is (responded? [200 {"content-length" "5" :http-version [1 1]} "Hello"]))))

(deftest matches-first-route-that-matches-requirements
  (with-endpoint
    (routing
     (match "/foo" fail)
     (match "/bar" hello-world)
     (match "/"    fail))

    (doseq [path ["/" "/foo"]]
      (GET path)
      (is (responded? fail-response)))

    (GET "/bar")
    (is (responded? hello-response))))

(deftest matches-method-requirements
  (with-endpoint
    (routing
     (match :GET  "/" fail)
     (match :POST "/" hello-world)
     (match "/" fail))

    (GET "/")
    (is (responded? fail-response))))

(deftest embedding-a-default-match
  (with-endpoint
    (routing
     (match "/zomg" fail)
     hello-world)

    (GET "/")
    (is (responded? hello-response))

    (GET "/zomg")
    (is (responded? fail-response))))

(deftest routes-are-anchored-by-default
  (with-endpoint
    (routing
     (match "/" hello-world))

    (GET "/hello")
    (is (responded? not-found-response))))

(deftest routes-extract-params
  (with-endpoint
    (routing
     (match "/:foo" echo-params)
     (match "/" hello-world))

    (GET "/hello")
    (is (= 204 (response-status)))
    (is (= {:foo "hello"} @params-received))

    (GET "/")
    (is (responded? hello-response))))

(deftest routes-with-multiple-params
  (with-endpoint
    (routing
     (match "/:foo/:bar" echo-params))

    (GET "/hello/world")
    (is (= 204 (response-status)))
    (is (= {:foo "hello" :bar "world"} @params-received))))

(deftest splat-arguments
  (with-endpoint
    (routing
     (match "/*foo" echo-params))

    (GET "/")
    (is (= 204 (response-status)))
    (is (= {:foo ""} @params-received))

    (GET "/foo")
    (is (= 204 (response-status)))
    (is (= {:foo "foo"} @params-received))

    (GET "/foo/bar")
    (is (= 204 (response-status)))
    (is (= {:foo "foo/bar"} @params-received))

    (GET "/foo/bar/")
    (is (= 204 (response-status)))
    (is (= {:foo "foo/bar"} @params-received))

    (GET "/foo///////b////b////")
    (is (= 204 (response-status)))
    (is (= {:foo "foo///////b////b"} @params-received))))

(deftest scoping-endpoints
  (with-endpoint
    (routing
     (scope "/zomg" echo-path))

    (GET "/zomg/hello")
    (is (= 204 (response-status)))
    (is (= {:path-info "/hello" :script-name "/zomg"} @path-info))

    (GET "/zomg")
    (is (= 204 (response-status)))
    (is (= {:path-info "" :script-name "/zomg"} @path-info))))

(deftest scoping-endpoints-with-params
  (with-endpoint
    (routing
     (scope "/zomg" echo-params))

    (GET "/zomg/hello")
    (is (= 204 (response-status)))
    (is (= {} @params-received)))

  (with-endpoint
    (routing
     (scope "/:blah" echo-params))

    (GET "/zomg")
    (is (= 204 (response-status)))
    (is (= {:blah "zomg"} @params-received))

    (GET "/zomg/bar")
    (is (= 204 (response-status)))
    (is (= {:blah "zomg"} @params-received))))

(deftest scoping-routes
  (with-endpoint
    (routing
     (scope "/foo"
       (match "/bar" hello-world)
       (match "/lulz/one" fail)))

    (GET "/foo")
    (is (responded? not-found-response))

    (GET "/foo/bar")
    (is (responded? hello-response))

    (GET "/foo/lulz/one")
    (is (responded? fail-response))))

(deftest scoping-routes-with-params
  (with-endpoint
    (routing
     (scope "/:foo" (match "/:bar" echo-params)))

    (GET "/one/two")
    (is (= 204 (response-status)))
    (is (= {:foo "one" :bar "two"} @params-received))))

(use-fixtures :each reset-params-atom)
