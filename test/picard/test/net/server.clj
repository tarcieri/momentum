(ns picard.test.net.server
  (:use
   clojure.test
   support.helpers
   picard.net.server))

(defcoretest simple-echo-server
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :message evt)
         (dn :message val)))))

  (is (next-msgs ch1 :open nil))

  (write-socket "Hello world")
  (is (next-msgs ch1 :message "Hello world"))
  (is (receiving "Hello world"))

  (close-socket)
  (is (next-msgs ch1 :close nil)))

(defcoretest sending-close-event-closes-connection
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :open evt)
         (dn :message "Hello world")
         (dn :close nil)))))

  (is (receiving "Hello world"))
  (Thread/sleep 50)
  (is (not (open-socket?)))
  (is (next-msgs
       ch1
       :open nil
       :close nil)))

(defcoretest writing-to-closed-socket
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :open evt)
         (send-off (agent nil)
           (Thread/sleep 30)
           (dn :message "Hello"))))))

  (close-socket)
  (is (next-msgs
       ch1
       :open   nil
       :close  nil
       :abort  #(instance? java.io.IOException %))))

(defcoretest handling-exception-in-bind-function
  [ch1]
  (start
   (fn [dn] (throw (Exception. "TROLLOLOL"))))

  (Thread/sleep 30)
  (is (not (open-socket?))))

(defcoretest handling-exception-after-open-event
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :open evt)
         (throw (Exception. "TROLLOLOL"))))))

  (is (next-msgs
       ch1
       :open  nil
       :abort #(instance? Exception %))))

(defcoretest handling-exception-after-message-event
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :message evt)
         (throw (Exception. "TROLLOLOL"))))))

  (write-socket "Hello world")

  (is (next-msgs
       ch1
       :open    nil
       :message "Hello world"
       :abort   #(instance? Exception %))))

