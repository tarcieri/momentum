(ns picard.test.net.client
  (:use
   clojure.test
   support.helpers
   picard.net.client)
  (:require
   [picard.net.server :as server]))

(defn- start-echo-server
  ([] (start-echo-server nil))
  ([ch]
     (server/start
      (fn [dn]
        (fn [evt val]
          (when ch (enqueue ch [evt val]))
          (when (= :message evt)
            (dn :message val)))))))

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

(defcoretest writing-to-closed-socket
  [ch1]
  (server/start
   (fn [dn]
     (fn [evt val]
       (when (= :open evt)
         (dn :close nil)))))

  (connect
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :open evt)
         (send-off
          (agent nil)
          (Thread/sleep 30)
          (dn :message "Hello")))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :open   nil
       :close  nil
       :abort  #(instance? java.io.IOException %))))

(defcoretest handling-exception-in-bind-function
  [ch1]
  (start-echo-server ch1)

  (connect
   (fn [dn]
     (throw (Exception. "TROLLOLOL")))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :open  nil
       :close nil)))

(defcoretest handling-exception-after-open-event
  [ch1 ch2]
  (start-echo-server ch1)

  (connect
   (fn [dn]
     (fn [evt val]
       (enqueue ch2 [evt val])
       (when (= :open evt)
         (throw (Exception. "TROLLOLOL")))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :open  nil
       :close nil))

  (is (next-msgs
       ch2
       :open  nil
       :abort #(instance? Exception %))))

(defcoretest handling-exception-after-message-event
  [ch1 ch2]
  (start-echo-server ch1)

  (connect
   (fn [dn]
     (fn [evt val]
       (enqueue ch2 [evt val])
       (when (= :open evt)
         (dn :message "Hello world"))
       (when (= :message evt)
         (throw (Exception. "TROLLOLOL")))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :open    nil
       :message "Hello world"
       :close   nil))

  (is (next-msgs
       ch2
       :open    nil
       :message "Hello world"
       :abort   #(instance? Exception %))))
