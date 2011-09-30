(ns picard.test.http.client
  (:require
   [picard.net.server  :as net]
   [picard.http.server :as server])
  (:use
   clojure.test
   support.helpers
   picard.utils.helpers
   picard.http.client))

(defn- start-hello-world-app
  [ch]
  (server/start
   (fn [dn]
     (fn [evt val]
       (enqueue ch [evt val])
       (when (= :request evt)
         (dn :response [200 {"content-type"   "text/plain"
                             "content-length" "5"
                             "connection"     "close"} "Hello"]))))))

(defcoretest simple-requests
  [ch1 ch2]
  (start-hello-world-app ch1)

  (doseq [method ["GET" "POST" "PUT" "DELETE"]]
    (connect
     (fn [dn]
       (fn [evt val]
         (enqueue ch2 [evt val])
         (when (= :abort evt)
           (.printStackTrace val))
         (when (= :open evt)
           (dn :request [{:path-info "/" :request-method method} ""]))))
     {:host "localhost" :port 4040})

    (is (next-msgs
         ch1
         :request [{:http-version   [1 1]
                    :script-name    ""
                    :path-info      "/"
                    :query-string   ""
                    :request-method method
                    :remote-addr    :dont-care
                    :local-addr     :dont-care} nil]
         :done nil))

    (is (next-msgs
         ch2
         :open     {:local-addr  ["127.0.0.1" #(number? %)]
                    :remote-addr ["127.0.0.1" #(number? %)]}
         :response [200 {:http-version    [1 1]
                         "content-length" "5"
                         "content-type"   "text/plain"
                         "connection"     "close"} "Hello"]
         :done nil))))

(defcoretest query-string-request
  [ch1 ch2]
  (start-hello-world-app ch1)

  (connect
   (fn [dn]
     (fn [evt val]
       (enqueue ch2 [evt val])
       (when (= :open evt)
         (dn :request [{:request-method "GET"
                        :path-info      "/"
                        :query-string   "zomg"}]))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :request [{:http-version   [1 1]
                  :script-name    ""
                  :path-info      "/"
                  :query-string   "zomg"
                  :request-method "GET"
                  :remote-addr    :dont-care
                  :local-addr     :dont-care} nil]
       :done nil))

  (is (next-msgs
       ch2
       :open :dont-care
       :response [200 {:http-version [1 1]
                       "content-length" "5"
                       "content-type"   "text/plain"
                       "connection"     "close"} "Hello"]
       :done nil)))

(defcoretest request-and-response-with-duplicated-headers
  [ch1 ch2]
  (server/start
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [200 {"content-length" "0"
                             "connection"     "close"
                             "foo"            "lol"
                             "bar"            ["omg" "hi2u"]
                             "baz"            ["1" "2" "3"]} ""])))))

  (connect
   (fn [dn]
     (fn [evt val]
       (enqueue ch2 [evt val])
       (when (= :open evt)
         (dn :request [{:request-method "GET"
                        :path-info      "/"
                        "baz"           "lol"
                        "bar"           ["omg" "hi2u"]
                        "lol"           ["1" "2" "3"]}]))))
   {:host "127.0.0.1" :port 4040})

  (is (next-msgs
       ch1
       :request [#(includes-hdrs {"baz" "lol"
                                  "bar" ["omg" "hi2u"]
                                  "lol" ["1" "2" "3"]} %) nil]
       :done nil))

  (is (next-msgs
       ch2
       :open :dont-care
       :response [200 #(includes-hdrs {"foo" "lol"
                                       "bar" ["omg" "hi2u"]
                                       "baz" ["1" "2" "3"]} %) ""]
       :done nil)))

(defcoretest receiving-a-chunked-body
  [ch1]
  (server/start
   (fn [dn]
     (fn [evt val]
       (when (= :request evt)
         (dn :response [200 {"transfer-encoding" "chunked"} :chunked])
         (dn :body "Hello")
         (dn :body "World")
         (dn :body nil)))))

  (connect
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :open evt)
         (dn :request [{:request-method "GET"
                        :path-info      "/"
                        "connection"    "close"}]))))
   {:host "127.0.0.1" :port 4040})

  (is (next-msgs
       ch1
       :open     :dont-care
       :response [200 #(includes-hdrs {"transfer-encoding" "chunked"} %) :chunked]
       :body     "Hello"
       :body     "World"
       :body     nil
       :done     nil))

  (is (no-msgs ch1)))

(defcoretest sending-a-chunked-body
  [ch1 ch2]
  (start-hello-world-app ch1)

  (connect
   (fn [dn]
     (fn [evt val]
       (enqueue ch2 [evt val])
       (when (= :open evt)
         (dn :request [{:request-method     "GET"
                        :path-info          "/"
                        "transfer-encoding" "chunked"} :chunked])
         (dn :body "Foo!")
         (dn :body "Bar!")
         (dn :body nil))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :request [#(includes-hdrs {"transfer-encoding" "chunked"} %) :chunked]
       :body    "Foo!"
       :body    "Bar!"
       :body    nil
       :done    nil))

  (is (next-msgs
       ch2
       :open     :dont-care
       :response [200 :dont-care "Hello"]
       :done     nil)))

(defcoretest simple-keep-alive-requests
  [ch1 ch2]
  (net/start
   (build-stack
    (fn [app]
      (fn [dn]
        (enqueue ch1 [:binding nil])
        (app dn)))
    server/proto
    (fn [dn]
      (fn [evt val]
        (when (= :request evt)
          (dn :response [200 {"content-length" "5"} "Hello"]))))))

  (dotimes [_ 2]
    (connect
     (fn [dn]
       (fn [evt val]
         (enqueue ch2 [evt val])
         (when (= :open evt)
           (dn :request [{:request-method "GET" :path-info "/"} nil]))))
     {:host "localhost" :port 4040})

    (is (next-msgs
         ch2
         :open     :dont-care
         :response [200 {:http-version [1 1] "content-length" "5"} "Hello"]
         :done     nil))

    (Thread/sleep 100))

  (connect
   (fn [dn]
     (fn [evt val]
       (enqueue ch2 [evt val])
       (when (= :open evt)
         (dn :request [{:request-method "GET" :path-info "/zomg"
                        "transfer-encoding" "chunked" "connection" "close"} :chunked])
         (dn :body "HELLO")
         (dn :body "WORLD")
         (dn :body nil))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch2
       :open     :dont-care
       :response [200 :dont-care "Hello"]
       :done     nil))

  (is (next-msgs ch1 :binding nil))
  (is (no-msgs ch1 ch2)))
