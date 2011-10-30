(ns picard.test.http.router
  (:use
   clojure.test
   picard.core
   picard.http.test
   picard.http.router))

(def not-found-response
  [404 {"content-length" "9"} (buffer "Not found")])

(def hello-response
  [200 {"content-length" "5"} (buffer "Hello")])

(defn- params
  [& params]
  [204 {:params (apply hash-map params)} nil])

(defn- hello-world
  [dn]
  (fn [evt val]
    (when (= :request evt)
      (dn :response hello-response))))

(defn- echo-params
  [dn]
  (fn [evt val]
    (when (= :request evt)
      (dn :response [204 {:params (-> val first :params)} nil]))))

(def fail-response
  [200 {"content-length" "4"} (buffer "fail")])

(defn- fail
  [dn]
  (fn [evt val]
    (when (= :request evt)
      (dn :response fail-response))))

(deftest single-route-router
  (with-app
    (routing
     (match "/" hello-world))

    (GET "/")
    (is (responded? [200 {"content-length" "5"} "Hello"]))))

(deftest matches-first-route-that-matches-requirements
  (with-app
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
  (with-app
    (routing
     (match :GET  "/" fail)
     (match :POST "/" hello-world)
     (match "/" fail))

    (GET "/")
    (is (responded? fail-response))))

(deftest embedding-a-default-match
  (with-app
    (routing
     (match "/zomg" fail)
     hello-world)

    (GET "/")
    (is (responded? hello-response))

    (GET "/zomg")
    (is (responded? fail-response))))

(deftest routes-are-anchored-by-default
  (with-app
    (routing
     (match "/" hello-world))

    (GET "/hello")
    (is (responded? not-found-response))))

(deftest routes-extract-params
  (with-app
    (routing
     (match "/:foo" echo-params)
     (match "/" hello-world))

    (GET "/hello")
    (is (responded? (params :foo "hello")))

    (GET "/")
    (is (responded? hello-response))))

(deftest routes-with-multiple-params
  (with-app
    (routing
     (match "/:foo/:bar" echo-params))

    (GET "/hello/world")
    (is (responded? (params :foo "hello" :bar "world")))))

(deftest splat-arguments
  (with-app
    (routing
     (match "/*foo" echo-params))

    (GET "/")
    (is (responded? (params :foo "")))

    (GET "/foo")
    (is (responded? (params :foo "foo")))

    (GET "/foo/bar")
    (is (responded? (params :foo "foo/bar")))

    (GET "/foo/bar/")
    (is (responded? (params :foo "foo/bar")))

    (GET "/foo///////b////b////")
    (is (responded? (params :foo "foo///////b////b")))))
