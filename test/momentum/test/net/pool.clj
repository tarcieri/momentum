(ns momentum.test.net.pool
  (:use
   clojure.test
   support.helpers
   momentum.core
   momentum.net.client)
  (:require
   [momentum.net.pool   :as pool]
   [momentum.net.server :as server])
  (:import
   [java.io
    IOException]))

;; === Data structure tests

(deftest adding-and-removing-a-single-connection
  (let [pool (pool/mk-pool {})
        conn (pool/mk-connection pool ["a.com" 80])]
    (is (= conn (pool/put pool conn)))
    (is (nil? (pool/poll pool ["b.com" 80])))
    (is (= conn (pool/poll pool ["a.com" 80])))
    (is (nil? (pool/poll pool ["a.com" 80])))))

(deftest adding-and-removing-multiple-unrelated-connections
  (let [pool  (pool/mk-pool {})
        conn1 (pool/mk-connection pool ["a.com" 80])
        conn2 (pool/mk-connection pool ["b.com" 80])]
    (pool/put pool conn1)
    (pool/put pool conn2)

    (is (= conn1 (pool/poll pool ["a.com" 80])))
    (pool/put pool conn1)

    (is (= conn2 (pool/poll pool ["b.com" 80])))))

(deftest adding-and-removing-multiple-related-connections
  (let [pool  (pool/mk-pool {})
        conn1 (pool/mk-connection pool ["a.com" 80])
        conn2 (pool/mk-connection pool ["a.com" 80])]
    (pool/put pool conn1)
    (pool/put pool conn2)

    (is (= conn2 (pool/poll pool ["a.com" 80])))

    (pool/put pool conn2)

    (is (= conn2 (pool/poll pool ["a.com" 80])))
    (is (= conn1 (pool/poll pool ["a.com" 80])))))

(deftest dropping-connection-multiple-times
  (let [pool  (pool/mk-pool {})
        conn1 (pool/mk-connection pool ["a.com" 80])
        conn2 (pool/mk-connection pool ["a.com" 80])
        conn3 (pool/mk-connection pool ["a.com" 80])]
    (pool/put pool conn1)
    (pool/put pool conn2)
    (pool/put pool conn3)

    ;; Drop twice
    (pool/drop pool conn2)
    (pool/drop pool conn2)

    (is (= conn3 (pool/poll pool ["a.com" 80])))
    (is (= conn1 (pool/poll pool ["a.com" 80])))))

(deftest purging-connections
  (let [pool  (pool/mk-pool {})
        conn1 (pool/mk-connection pool ["a.com" 80])
        conn2 (pool/mk-connection pool ["a.com" 80])
        conn3 (pool/mk-connection pool ["b.com" 80])]

    (is (nil? (pool/purge pool)))

    (pool/put pool conn1)
    (pool/put pool conn2)
    (pool/put pool conn3)

    (is (= conn1 (pool/purge pool)))
    (is (= conn2 (pool/purge pool)))
    (is (= conn3 (pool/purge pool)))))

;; === Full stack tests

(defn- echo-server
  [ch]
  (fn [dn _]
    (fn [evt val]
      (when ch (enqueue ch [evt val]))
      (when (= :message evt)
        (dn :message val)))))

(defn- slow-echo-server
  [ch]
  (fn [dn _]
    (fn [evt val]
      (when ch (enqueue ch [evt val]))
      (when (= :message evt)
        (future
          (Thread/sleep 100)
          (dn :message val))))))

(defn- start
  ([server] (start server nil {}))
  ([server ch-or-opts]
     (if (map? ch-or-opts)
       (start server nil ch-or-opts)
       (start server ch-or-opts {})))
  ([server ch opts] (server/start (server ch) opts)))

(def server-addr-info
  {:local-addr  ["127.0.0.1" 4040]
   :remote-addr ["127.0.0.1" :dont-care]})

(def client-addr-info
  {:local-addr     ["127.0.0.1" :dont-care]
   :remote-addr    ["127.0.0.1" 4040]
   :exchange-count :dont-care})

(defcoretest simple-exchanges
  [ch1 ch2]
  (start echo-server ch1)

  (let [connect (client {:pool true})]
    (dotimes [i 2]
      (Thread/sleep 50)
      (connect
       (fn [dn _]
         (enqueue ch2 [:binding nil])
         (fn [evt val]
           (enqueue ch2 [evt val])
           (when (= :open evt)
             (dn :message (buffer (str "ZOMG! " i))))
           (when (= :message evt)
             (dn :close true))))
       {:host "localhost" :port 4040})

      (is (next-msgs
           ch1
           :open    server-addr-info
           :message (str "ZOMG! " i)
           :close   nil))

      (is (next-msgs
           ch2
           :binding nil
           :open    (assoc client-addr-info :exchange-count 1)
           :message (str "ZOMG! " i)
           :close   nil)))))

(defn- run-echo-client
  [ch connect msg]
  (connect
   (fn [dn _]
     (enqueue ch [:binding nil])
     (fn [evt val]
       (enqueue ch [evt val])
       (when (= :open evt)
         (dn :message msg))
       (when (= :message evt)
         (dn :close nil))))
   {:host "localhost" :port 4040}))

(defcoretest simple-pooled-client
  [ch1 ch2]
  (start echo-server ch1)

  (let [pool (client {:pool true})]
    (run-echo-client ch2 pool (buffer "Hello world"))

    (is (next-msgs
         ch1
         :open    server-addr-info
         :message "Hello world"))

    (is (next-msgs
         ch2
         :binding nil
         :open    (assoc client-addr-info :exchange-count 1)
         :message "Hello world"
         :close   nil))

    (Thread/sleep 50)

    (run-echo-client ch2 pool (buffer "Goodbye world"))

    (is (next-msgs ch1 :message "Goodbye world"))

    (is (next-msgs
         ch2
         :binding nil
         :open    (assoc client-addr-info :exchange-count 2)
         :message "Goodbye world"
         :close   nil))))

(defcoretest simple-pooled-client-close-false
  [ch1 ch2]
  (start echo-server ch1)

  (let [pool (client {:pool true})]
    (pool
     (fn [dn _]
       (enqueue ch2 [:binding nil])
       (fn [evt val]
         (enqueue ch2 [evt val])
         (when (= :open evt)
           (dn :message (buffer "Hello world")))
         (when (= :message evt)
           (dn :close false))))
     {:host "localhost" :port 4040})

    (is (next-msgs
         ch1
         :open    server-addr-info
         :message "Hello world"))

    (is (next-msgs
         ch2
         :binding nil
         :open    (assoc client-addr-info :exchange-count 1)
         :message "Hello world"
         :close   nil))

    (Thread/sleep 50)

    (run-echo-client ch2 pool (buffer "Goodbye world"))

    (is (next-msgs ch1 :message "Goodbye world"))

    (is (next-msgs
         ch2
         :binding nil
         :open    (assoc client-addr-info :exchange-count 2)
         :message "Goodbye world"
         :close   nil))))

(defcoretest requests-to-the-same-host-in-parallel
  [ch1 ch2 ch3]
  (server/start
   (fn [dn _]
     (enqueue ch1 [:binding nil])
     (fn [evt val]
       (when (= :message evt)
         (future
           (Thread/sleep 10)
           (dn :message val))))))

  (let [connect (client {:pool true})]
    (doseq [ch [ch2 ch3]]
      (connect
       (fn [dn _]
         (fn [evt val]
           (enqueue ch [evt val])
           (when (= :open evt)
             (dn :message (buffer "ZOMG!")))
           (when (= :message evt)
             (dn :close nil))))
       {:host "localhost" :port 4040}))

    (is (next-msgs
         ch1
         :binding nil
         :binding nil))

    (is (next-msgs
           ch2
           :open    (assoc client-addr-info :exchange-count 1)
           :message "ZOMG!"
           :close   nil))

    (is (next-msgs
           ch3
           :open    (assoc client-addr-info :exchange-count 1)
           :message "ZOMG!"
           :close   nil))))

(defcoretest connecting-to-a-server-that-closes-the-connection
  [ch1 ch2]
  (server/start
   (fn [dn _]
     (enqueue ch1 [:binding nil])
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :message evt)
         (dn :message val)
         (dn :close nil)))))

  (let [connect (client {:pool true})]
    (dotimes [i 2]
      (Thread/sleep 50)
      (connect
       (fn [dn _]
         (fn [evt val]
           (enqueue ch2 [evt val])
           (when (= :open evt)
             (dn :message (buffer (str "Zomg! " i))))))
       {:host "localhost" :port 4040})

      (is (next-msgs
           ch1
           :binding nil
           :open    server-addr-info
           :message (str "Zomg! " i)
           :close   nil))

      (is (next-msgs
           ch2
           :open    (assoc client-addr-info :exchange-count 1)
           :message (str "Zomg! " i)
           :close   nil)))))

(defcoretest closed-connections-are-removed-from-pool
  [ch1 ch2 ch3]
  (server/start
   (fn [dn _]
     (enqueue ch1 [:binding nil])
     (fn [evt val]
       (when (= :message evt)
         (dn :message val)
         (future
           (Thread/sleep 10)
           (dn :close nil))))))

  (let [connect (client {:pool true})]
    (dotimes [_ 2]
      (Thread/sleep 50)

      (connect
       (fn [dn _]
         (fn [evt val]
           (when (= :abort evt)
             (enqueue ch3 [evt val]))

           (when (= :open evt)
             (dn :message (buffer "ZOMG")))

           (when (= :message evt)
             (enqueue ch2 [:success nil])
             (dn :close nil))))
       {:host "localhost" :port 4040})

      (is (next-msgs ch2 :success nil))))

  (is (next-msgs ch1 :binding nil :binding nil))
  (is (no-msgs ch3)))

(defcoretest connecting-to-invalid-host
  [ch1]
  nil

  (let [connect (client {:pool true})]
    (connect
     (fn [dn _]
       (fn [evt val]
         (enqueue ch1 [evt val])))
     ;; Hopefully this is an invalid IP address and port
     {:host "192.168.32.123" :port 13845})

    (Thread/sleep 1100)

    (is (next-msgs ch1 :abort #(instance? Exception %)))
    (is (no-msgs ch1))))

(defcoretest observing-max-connections
  [ch1 ch2]
  (start slow-echo-server)

  (let [connect (client {:pool {:max-conns 1}})]
    (doseq [ch [ch1 ch2]]
      (connect
       (fn [dn _]
         (fn [evt val]
           (enqueue ch [evt val])
           (when (= :open evt)
             (dn :message (buffer "Hello")))
           (when (= :message evt)
             (dn :close nil))))
       {:host "localhost" :port 4040}))

    (is (next-msgs
         ch1
         :open    :dont-care
         :message "Hello"
         :close   nil))

    (is (next-msgs ch2 :abort #(instance? Exception %)))))

(defcoretest pool-count-decrements-when-connections-expire
  [ch1 ch2]
  (server/start
   (fn [dn _]
     (fn [evt val]
       (when (= :message evt)
         (dn :message val)
         (future
           (Thread/sleep 50)
           (dn :close nil))))))

  (let [connect (client {:pool {:max-conns 1}})]
    (doseq [ch [ch1 ch2]]
      (connect
       (fn [dn _]
         (fn [evt val]
           (enqueue ch [evt val])
           (when (= :open evt)
             (dn :message (buffer "Hello")))

           (when (= :message evt)
             (dn :close nil))))
       {:host "localhost" :port 4040})

      (Thread/sleep 100))

    (is (next-msgs
         ch1
         :open    #(includes-hdrs {:exchange-count 1} %)
         :message "Hello"
         :close   nil))

    (is (next-msgs
         ch2
         :open    #(includes-hdrs {:exchange-count 1} %)
         :message "Hello"
         :close   nil))))

(defcoretest doesnt-double-increment-connection-counting
  [ch1 ch2 ch3]
  (start slow-echo-server)

  (let [connect (client {:pool {:max-conns 2}})]
    (doseq [ch [ch1 ch2 ch3]]
      (connect
       (fn [dn _]
         (fn [evt val]
           (enqueue ch [evt val])
           (when (= :open evt)
             (dn :message (buffer "Hello")))
           (when (= :message evt)
             (dn :close nil))))
       {:host "localhost" :port 4040}))

    (is (next-msgs
         ch1
         :open    :dont-care
         :message "Hello"
         :close   nil))

    (is (next-msgs
         ch2
         :open    :dont-care
         :message "Hello"
         :close   nil))

    (is (next-msgs ch3 :abort #(instance? Exception %)))))

(defcoretest observing-max-connections-per-address
  [ch1 ch2 ch3]
  (vector
   (start slow-echo-server {:port 4040})
   (start slow-echo-server {:port 4041}))

  (let [connect (client {:pool {:max-conns-per-addr 1}})]
    (doseq [ch [ch1 ch2 ch3]]
      (connect
       (fn [dn _]
         (fn [evt val]
           (enqueue ch [evt val])
           (when (= :open evt)
             (dn :message (buffer "Hello")))
           (when (= :message evt)
             (dn :close nil))))
       {:host "localhost" :port (if (= ch3 ch) 4040 4041)}))

    (is (next-msgs
         ch1
         :open    :dont-care
         :message "Hello"
         :close   nil))

    (is (next-msgs
         ch3
         :open    :dont-care
         :message "Hello"
         :close   nil))

    (is (next-msgs ch2 :abort #(instance? Exception %)))))

(defcoretest purging-unused-connections
  [ch1 ch2]
  (vector
   (start echo-server {:port 4040})
   (start echo-server {:port 4041}))

  (let [connect (client {:pool {:max-conns 1}})]
    (doseq [ch [ch1 ch2]]
      (connect
       (fn [dn _]
         (fn [evt val]
           (enqueue ch [evt val])
           (when (= :open evt)
             (dn :message (buffer "Hello")))
           (when (= :message evt)
             (dn :close nil))))
       {:host "localhost" :port (if (= ch1 ch) 4040 4041)})

      (is (next-msgs
           ch
           :open    :dont-care
           :message "Hello"
           :close   nil)))))

(defcoretest timing-out-stale-connections-after-keepalve
  [ch1 ch2]
  (server/start
   (fn [dn _]
     (enqueue ch1 [:binding nil])
     (fn [evt val]
       (when (= :message evt)
         (dn :message val)))))

  (let [connect (client {:pool {:keepalive 1}})]
    (dotimes [_ 2]
      (run-echo-client ch2 connect (buffer "Hello"))

      (is (next-msgs
           ch2
           :binding nil
           :open    :dont-care
           :message "Hello"
           :close   nil))

      (Thread/sleep 2000)))

  (is (next-msgs ch1 :binding nil :binding nil)))

(defcoretest reopening-connections-that-have-been-closed
  [ch1 ch2]
  (server/start
   (fn [dn _]
     (fn [evt val]
       (when (= :message evt)
         (dn :message val)
         (dn :close nil)))))

  (let [connect (client {:pool true})]
    (dotimes [_ 30]
      (connect
       (fn [dn _]
         (enqueue ch1 [:binding nil])
         (let [reopened? (atom false)
               pong?     (atom false)]
           (fn [evt val]
             ;; There won't always be a race condition, so when there
             ;; isn't, we want to normalize the output.
             (when (and (= :message evt) (not @reopened?))
               (enqueue ch1 [:open {}]))

             (cond
              (instance? IOException val)
              (do
                (reset! reopened? true)
                (dn :reopen nil))

              (= :open evt)
              (do
                (enqueue ch1 [evt val])
                (dn :message (buffer "Hello")))

              (= :message evt)
              (do
                (reset! pong? true)
                (enqueue ch1 [evt val])
                (dn :close nil))

              (= :close evt)
              (if @pong?
                (enqueue ch1 [evt val])
                (do
                  (reset! reopened? true)
                  (dn :reopen nil)))))))
       {:host "localhost" :port 4040})

      (is (next-msgs
           ch1
           :binding nil
           :open    :dont-care
           :open    :dont-care
           :message "Hello"
           :close   nil)))))
