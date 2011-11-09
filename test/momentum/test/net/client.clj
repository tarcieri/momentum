(ns momentum.test.net.client
  (:use
   clojure.test
   support.helpers
   momentum.core
   momentum.net.client)
  (:require
   [momentum.net.server :as server])
  (:import
   [java.net
    ConnectException]))

(defn- start-echo-server
  ([] (start-echo-server nil))
  ([ch]
     (server/start
      (fn [dn _]
        (fn [evt val]
          (when ch (enqueue ch [evt val]))
          (when (= :message evt)
            (dn :message val)))))))

(def server-addr-info
  {:local-addr  ["127.0.0.1" 4040]
   :remote-addr ["127.0.0.1" :dont-care]})

(def client-addr-info
  {:local-addr  ["127.0.0.1" :dont-care]
   :remote-addr ["127.0.0.1" 4040]})

(defcoretest simple-echo-client
  [ch1 ch2 ch3]
  (start-echo-server ch1)

  (enqueue ch3
           [:message (buffer "Hello world")]
           [:message (buffer "Goodbye world")]
           [:close   nil])

  (connect
   (fn [dn _]
     (fn [evt val]
       (enqueue ch2 [evt val])
       (apply dn (first (seq ch3)))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :open    server-addr-info
       :message "Hello world"))

  (is (next-msgs
       ch2
       :open    client-addr-info
       :message "Hello world"))

  (is (next-msgs ch1 :message "Goodbye world"))
  (is (next-msgs ch2 :message "Goodbye world"))
  (is (next-msgs ch1 :close nil))
  (is (next-msgs ch2 :close nil)))

(defcoretest writing-to-closed-socket
  [ch1]
  (server/start
   (fn [dn _]
     (fn [evt val]
       (when (= :open evt)
         (dn :close nil)))))

  (connect
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :open evt)
         (future
           (Thread/sleep 60)
           (try
             (dn :message (buffer "Hello"))
             (catch Exception err
               (enqueue ch1 [:abort err])))))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :open   client-addr-info
       :close  nil
       :abort  #(instance? java.io.IOException %))))

(defcoretest connecting-to-invalid-host
  [ch1]
  nil

  (connect
   (fn [dn _]
     (enqueue ch1 [:binding nil])
     (fn [evt val]
       (enqueue ch1 [evt val])))
   ;; Hopefully this is an invalid IP address and port
   {:host "192.168.32.123" :port 13845})

  (Thread/sleep 2100)
  (is (next-msgs
       ch1
       :binding nil
       :abort   #(instance? ConnectException %))))

(defcoretest handling-exception-in-bind-function
  [ch1 ch2]
  (start-echo-server ch1)

  (try
    (connect
     (fn [dn _]
       (throw (Exception. "TROLLOLOL")))
     {:host "localhost" :port 4040})
    (catch Exception e
      (enqueue ch2 [:exception e])))

  (is (no-msgs ch1))

  (is (next-msgs ch2 :exception #(instance? Exception %))))

(defcoretest handling-exception-after-open-event
  [ch1 ch2]
  (start-echo-server ch1)

  (connect
   (fn [dn _]
     (fn [evt val]
       (enqueue ch2 [evt val])
       (when (= :open evt)
         (throw (Exception. "TROLLOLOL")))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :open  server-addr-info
       :close nil))

  (is (next-msgs
       ch2
       :open  client-addr-info
       :abort #(instance? Exception %))))

(defcoretest handling-exception-after-message-event
  [ch1 ch2]
  (start-echo-server ch1)

  (connect
   (fn [dn _]
     (fn [evt val]
       (enqueue ch2 [evt val])
       (when (= :open evt)
         (dn :message (buffer "Hello world")))
       (when (= :message evt)
         (throw (Exception. "TROLLOLOL")))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :open    server-addr-info
       :message "Hello world"
       :close   nil))

  (is (next-msgs
       ch2
       :open    client-addr-info
       :message "Hello world"
       :abort   #(instance? Exception %))))

(defcoretest handling-exception-after-abort-event
  [ch1 ch2]
  (start-echo-server ch1)

  (connect
   (fn [dn _]
     (fn [evt val]
       (enqueue ch2 [evt val])
       (when (#{:open :abort} evt)
         (throw (Exception. "TROLLOLOL")))))
   {:host "localhost" :port 4040}))

(defcoretest abort-messages-get-prioritized-over-other-events
  [ch1 ch2 ch3]
  (start-echo-server ch1)

  (connect
   (fn [dn _]
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
       :open  server-addr-info
       :close nil))

  (is (next-msgs
       ch2
       :open  client-addr-info
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
   (fn [dn _]
     (fn [evt val]
       (enqueue ch2 [evt val])
       (when (= :open evt)
         (dn :message (buffer "Hello world")))
       (when (= :message evt)
         (dn :close nil)
         (throw (Exception. "TROLLOLOL")))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :open    server-addr-info
       :message "Hello world"
       :close   nil)))

(defn- start-black-hole-server
  [ch]
  (server/start
   (fn [dn _]
     (doasync (seq ch)
       (fn [_] (dn :resume nil)))
     (fn [evt val]
       (when (= :open evt)
         (dn :pause nil))))))

(defcoretest telling-the-application-to-chill-out
  [ch1 ch2]
  (start-black-hole-server ch2)

  (connect
   (fn [dn _]
     (let [latch (atom true)]
       (fn [evt val]
         (enqueue ch1 [evt val])
         (cond
          (= :open evt)
          (future
            (loop [continue? @latch]
              (when continue?
                (dn :message (buffer "HAMMER TIME!"))
                (recur @latch))))

          (= :pause evt)
          (reset! latch false)

          (= :resume evt)
          (dn :close nil)))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :open   client-addr-info
       :pause  nil))

  (enqueue ch2 :resume)

  (is (next-msgs
       ch1
       :resume nil
       :close  nil)))

(defcoretest raising-error-during-pause-event
  [ch1 ch2]
  (start-black-hole-server ch2)

  (connect
   (fn [dn _]
     (let [latch (atom true)]
       (fn [evt val]
         (enqueue ch1 [evt val])

         (when (= :open evt)
           (future
             (loop [continue? @latch]
               (when continue?
                 (dn :message (buffer "HAMMER TIME!"))
                 (recur @latch)))))

         (when (= :pause evt)
           (reset! latch false)
           (throw (Exception. "TROLLOLOL"))))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :open   client-addr-info
       :pause  nil
       :abort #(instance? Exception %))))

(defcoretest raising-error-during-resume-event
  [ch1 ch2]
  (start-black-hole-server ch2)

  (connect
   (fn [dn _]
     (let [latch (atom true)]
       (fn [evt val]
         (enqueue ch1 [evt val])
         (cond
          (= :open evt)
          (future
            (loop [continue? @latch]
              (when continue?
                (dn :message (buffer "HAMMER TIME!"))
                (recur @latch))))

          (= :pause evt)
          (reset! latch false)

          (= :resume evt)
          (throw (Exception. "TROLLOLOL"))))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :open   client-addr-info
       :pause  nil))

  (enqueue ch2 :resume)

  (is (next-msgs
       ch1
       :resume nil
       :abort  #(instance? Exception %))))

(defcoretest telling-the-server-to-chill-out
  [ch1 ch2 ch3]
  (server/start
   (fn [dn _]
     (doasync (seq ch2)
       (fn [_]
         (dn :message (buffer "Goodbye world"))
         (dn :close nil)))
     (fn [evt val]
       (when (= :open evt)
         (dn :message (buffer "Hello world"))))))

  (connect
   (fn [dn _]
     (doasync (seq ch3)
       (fn [_] (dn :resume nil)))
     (let [latch (atom true)]
       (fn [evt val]
         (when-not (#{:pause :resume} evt)
           (enqueue ch1 [evt val]))

         (when (and (= :message evt) @latch)
           (dn :pause nil)
           (reset! latch false)))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :open    client-addr-info
       :message "Hello world"))

  (enqueue ch2 :message)

  (is (no-msgs ch1))

  (enqueue ch3 :resume)

  (is (next-msgs
       ch1
       :message "Goodbye world")))

(defcoretest avoiding-abort-loops
  [ch1]
  (start-echo-server)

  (connect
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (dn :abort (Exception. "TROLLOLOL"))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :open  client-addr-info
       :abort #(instance? Exception %)))

  (is (no-msgs ch1)))
