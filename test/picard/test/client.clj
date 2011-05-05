(ns picard.test.client
  (:use
   [clojure.test]
   [lamina.core]
   [test-helper])
  (:require
   [picard.client :as client]))

(deftest simple-requests
  (println "simple-requests")
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
            :respond   [200
                        {"connection" "close"
                         "content-length" "5"}
                        "Hello"]))))))

(deftest receiving-a-chunked-body
  (println "receiving-a-chunked-body")
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
          :respond   [200 {"transfer-encoding" "chunked"} :chunked]
          :body      "Hello"
          :body      "World"
          :done      nil)))))

(deftest sending-a-chunked-body
  (println "sending-a-chunked-body")
  (with-channels
    [ch ch2]
    (running-app
     (fn [resp]
       (fn [evt val]
         (enqueue ch [evt val])
         (when (= :done evt)
           (resp :respond [200
                           {"connection" "close"
                            "content-length" "5"}
                           "Hello"]))))

     (client/request
      ["localhost" 4040]
      [{:path-info          "/"
        :request-method     "GET"
        "transfer-encoding" "chunked"} :chunked]
      (fn [upstream evt val]
        (enqueue ch2 [evt val])
        (when (= :connected evt)
          (upstream :body "Foo!")
          (upstream :body "Bar!")
          (upstream :done nil))))

     (is (next-msgs-for
          ch
          :request [(includes-hdrs {"transfer-encoding" "chunked"}) :chunked]
          :body    "Foo!"
          :body    "Bar!"
          :done    nil))

     (is (next-msgs-for
          ch2
          :connected nil
          :respond   [200 {"connection" "close" "content-length" "5"} "Hello"])))))

(deftest simple-keep-alive-requests
  (println "simple-keep-alive-requests")
  (with-channels
    [_ ch2]
    (running-hello-world-app
     (let [pool (client/mk-pool)]
       (client/request
        pool ["localhost" 4040]
        [{:path-info "/" :request-method "GET"}]
        (fn [_ evt val] (enqueue ch2 [evt val])))

       (is (next-msgs-for
            ch2
            :respond   [200 {"content-length" "5"} "Hello"]))

       (client/request
        pool ["localhost" 4040]
        [{:path-info "/" :request-method "GET"}]
        (fn [_ evt val] (enqueue ch2 [evt val])))

       (is (next-msgs-for
            ch2
            ;; :connected nil
            :respond   [200 {"content-length" "5"} "Hello"]))

       (client/request
        pool ["localhost" 4040]
        [{:path-info          "/zomg"
          :request-method     "POST"
          "transfer-encoding" "chunked"
          "connection"        "close"}
         :chunked]
        (fn [upstream-fn evt val]
          (enqueue ch2 [evt val])
          (when (= :connected evt)
            (upstream-fn :body "HELLO")
            (upstream-fn :body "WORLD")
            (upstream-fn :done nil))))

       (is (next-msgs-for
            ch2
            :connected nil
            :respond   [200 {"content-length" "5"} "Hello"]))

       ;; 2 is to account for the connection test-helper makes
       (is (= 2 (count (netty-connect-evts))))))))
