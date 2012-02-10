(ns momentum.test.http.test
  (:use
   clojure.test
   momentum.core
   momentum.http.test))

(deftest basic-tests
  (with-endpoint
    (fn [dn _]
      (fn [evt val]
        (if (= :request evt)
          (dn :response [200 {"content-length" "5"} (buffer "Hello")]))))

    (let [conn (GET "/")]
      (is (= (second (seq conn))
             [:response [200 {"content-length" "5" :http-version [1 1]} (buffer "Hello")]])))))
