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
    (doseq [method ["GET" "POST" "PUT" "DELETE" "HEAD"]]
      (running-app
       (fn [resp]
         (fn [evt val]
           (enqueue ch [evt val])
           (resp :respond [200
                           {"content-length" "5"
                            "connection" "close"}
                           "Hello"])))

       (client/request
        ["localhost" 4040]
        [{:path-info "/" :request-method method}]
        (fn [_ evt val]
          (enqueue ch2 [evt val])))

       (is (next-msgs-for
            ch
            :request [{:server-name    picard/SERVER-NAME
                       :script-name    ""
                       :path-info      "/"
                       :request-method method} nil]))
       (is (next-msgs-for
            ch2
            :connected nil
            :respond   [200
                        {"connection" "close"
                         "content-length" "5"}
                        "Hello"]))))))

(deftest receiving-a-chunked-body
  (with-channels
    [ch ch2]
    (running-app
     (fn [resp]
       (fn [evt val]
         (enqueue ch [evt val])
         (resp :respond [200
                         {"transfer-encoding" "chunked"}
                         :chunked])
         (resp :body "Hello")
         (resp :body "World")
         (resp :done nil)))

     (client/request
      ["localhost" 4040]
      [{:path-info      "/"
        :request-method "GET"
        "connection"    "close"}]
      (fn [_ evt val]
        (enqueue ch2 [evt val])))

     (is (next-msgs-for
          ch2
          :connected nil
          :respond   [200 {"transfer-encoding" "chunked"} :chunked]
          :body      "Hello"
          :body      "World"
          :done      nil)))))
