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
         (future
           (Thread/sleep 60)
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

(defcoretest handling-exception-after-abort-event
  [ch1 ch2]
  (start-echo-server ch1)

  (connect
   (fn [dn]
     (fn [evt val]
       (enqueue ch2 [evt val])
       (when (#{:open :abort} evt)
         (throw (Exception. "TROLLOLOL")))))
   {:host "localhost" :port 4040}))

(defcoretest abort-messages-get-prioritized-over-other-events
  [ch1 ch2 ch3]
  (start-echo-server ch1)

  (connect
   (fn [dn]
     (let [depth (atom 0)]
       (fn [evt val]
         (let [count (swap! depth inc)]
           (enqueue ch2 [evt val])
           (enqueue ch3 [:depth count])

           (when (= :open evt)
             (dn :close nil)
             (dn :abort (Exception. "TROLLOLOL")))
           (swap! depth dec)))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :open  nil
       :close nil))

  (is (next-msgs
       ch2
       :open  nil
       :abort #(instance? Exception %)))

  (is (next-msgs
       ch3
       :depth 1
       :depth 1))

  (is (no-msgs ch1 ch2 ch3)))

(defcoretest thrown-exceptions-get-prioritized-over-other-events
  [ch1 ch2]
  (start-echo-server ch1)

  (connect
   (fn [dn]
     (fn [evt val]
       (enqueue ch2 [evt val])
       (when (= :open evt)
         (dn :message "Hello world"))
       (when (= :message evt)
         (dn :close nil)
         (throw (Exception. "TROLLOLOL")))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :open    nil
       :message "Hello world"
       :close   nil)))

(defn- start-black-hole-server
  [ch1 ch2]
  (server/start
   (fn [dn]
     (receive ch2 (fn [_] (dn :resume nil)))
     (fn [evt val]
       (when (= :open evt)
         (dn :pause nil))))))

(defcoretest telling-the-application-to-chill-out
  [ch1 ch2]
  (start-black-hole-server ch1 ch2)

  (connect
   (fn [dn]
     (let [latch (atom true)]
       (fn [evt val]
         (enqueue ch1 [evt val])
         (cond
          (= :open evt)
          (future
            (loop [continue? @latch]
              (when continue?
                (dn :message "HAMMER TIME!")
                (recur @latch))))

          (= :pause evt)
          (reset! latch false)

          (= :resume evt)
          (dn :close nil)))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :open   nil
       :pause  nil))

  (enqueue ch2 :resume)

  (is (next-msgs
       ch1
       :resume nil
       :close  nil)))

(defcoretest raising-error-during-pause-event
  [ch1 ch2]
  (start-black-hole-server ch1 ch2)

  (connect
   (fn [dn]
     (let [latch (atom true)]
       (fn [evt val]
         (enqueue ch1 [evt val])

         (when (= :open evt)
           (future
             (loop [continue? @latch]
               (when continue?
                 (dn :message "HAMMER TIME!")
                 (recur @latch)))))

         (when (= :pause evt)
           (reset! latch false)
           (throw (Exception. "TROLLOLOL"))))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :open   nil
       :pause  nil
       :abort #(instance? Exception %))))
