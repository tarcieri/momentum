(ns picard.test.client
  (:use
   [clojure.test]
   [lamina.core]
   [test-helper])
  (:require
   [picard.client :as client]))

(deftest simple-requests
  (with-channels
    [ch ch2]
    (running-app
     (fn [resp]
       (println "+++ BINDING")
       (fn [evt val]
         (println "+++ [S] " [evt val])
         (enqueue ch [evt val])
         (resp :respond [200
                         {"content-length" "5"
                          "connection" "close"}
                         "Hello"])))

     (client/request
      ["localhost" 4040]
      [{:path-info "/" :request-method "GET"}]
      (fn [_ evt val]
        (println "+++ [C] " [evt val])
        (enqueue ch2 [evt val])))

     (is (next-msgs-for
          ch
          :request [{:server-name    picard/SERVER-NAME
                     :script-name    ""
                     :path-info      "/"
                     :request-method "GET"} nil]))
     (is (next-msgs-for
          ch2
          :connected nil
          :response [200
                     {"connection" "close"
                      "content-length" "5"}
                     "Hello"])))))
