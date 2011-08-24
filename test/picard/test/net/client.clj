(ns picard.test.net.client
  (:use
   clojure.test
   support.helpers
   picard.net.client)
  (:require
   [picard.net.server :as server]))

(defn- start-echo-server
  [ch]
  (server/start
   (fn [dn]
     (fn [evt val]
       (enqueue ch [evt val])
       (when (= :message evt)
         (dn :message val))))))

(defcoretest simple-echo-client
  [ch1 ch2 ch3]
  (start-echo-server ch1)

  (enqueue ch3
           [:message "Hello world"]
           [:message "Goodbye world"]
           [:close   nil])

  (connect
   (fn [dn]
     (fn [evt val]
       (enqueue ch2 [evt val])
       (receive ch3 #(apply dn %))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :open    nil
       :message "Hello world"))

  (is (next-msgs
       ch2
       :open    nil
       :message "Hello world"))

  (is (next-msgs ch1 :message "Goodbye world"))
  (is (next-msgs ch2 :message "Goodbye world"))
  (is (next-msgs ch1 :close nil))
  (is (next-msgs ch2 :close nil)))
