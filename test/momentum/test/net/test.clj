(ns momentum.test.net.test
  (:use
   clojure.test
   momentum.net.test))

(deftest simple-echo-client
  (with-app
    (fn [dn _]
      (fn [evt val]
        (when (= :message evt)
          (dn :message val))))

    (let [conn (open)]
      (conn :message "Hello")
      (is (= (first (received conn))
             (first conn)
             [:message "Hello"]))

      (conn :message "World")
      (is (= (first (received conn))
             (second conn)
             [:message "World"]))

      (conn :close nil)

      (is (= (seq conn)
             [[:message "Hello"]
              [:message "World"]])))))
