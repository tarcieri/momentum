(ns picard.test.http.test
  (:use
   clojure.test
   picard.http.test))

(defn- echo-app
  [dn]
  (fn [evt val]
    (if (= :request evt)
      (dn :response (cons 200 val))
      (dn evt val))))

(deftest basic-tests
  (with-app echo-app
    (let [conn (GET "/")]
      (is (= (first conn)
             [:response [200
                         {"host"          "example.org"
                          :http-version   [1 1]
                          :query-string   ""
                          :script-name    ""
                          :request-method "GET"
                          :path-info      "/"
                          :remote-addr    ["127.0.0.1" 1234]
                          :local-addr     ["127.0.0.1" 4321]} nil]])))))
