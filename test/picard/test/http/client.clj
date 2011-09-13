(ns picard.test.http.client
  (:require
   [picard.http.server :as server])
  (:use
   clojure.test
   support.helpers
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

  (connect
   (fn [dn]
     (fn [evt val]
       (enqueue ch2 [evt val])
       (when (= :abort evt)
         (.printStackTrace val))
       (when (= :open evt)
         (dn :request [{:path-info "/" :request-method "GET"} ""]))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :request [{:http-version   [1 1]
                  :script-name    ""
                  :path-info      "/"
                  :query-string   ""
                  :request-method "GET"
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
       :done nil)))
