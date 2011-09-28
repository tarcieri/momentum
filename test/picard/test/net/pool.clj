(ns picard.test.net.pool
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
          (when (= :abort evt)
            (.printStackTrace val))
          (when ch (enqueue ch [evt val]))
          (when (= :message evt)
            (dn :message val)))))))

(def server-addr-info
  {:local-addr  ["127.0.0.1" 4040]
   :remote-addr ["127.0.0.1" :dont-care]})

(def client-addr-info
  {:local-addr     ["127.0.0.1" :dont-care]
   :remote-addr    ["127.0.0.1" 4040]
   :exchange-count :dont-care})

(defcoretest simple-exchanges
  [ch1 ch2]
  (start-echo-server ch1)

  (let [connect (client {:pool true})]
    (dotimes [i 2]
      (Thread/sleep 50)
      (connect
       (fn [dn]
         (enqueue ch2 [:binding nil])
         (fn [evt val]
           (enqueue ch2 [evt val])
           (when (= :abort evt)
             (.printStackTrace val))
           (when (= :open evt)
             (dn :message (str "ZOMG! " i)))
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
   (fn [dn]
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
  (start-echo-server ch1)

  (let [pool (client {:pool true})]
    (run-echo-client ch2 pool "Hello world")

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

    (run-echo-client ch2 pool "Goodbye world")

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
   (fn [dn]
     (enqueue ch1 [:binding nil])
     (fn [evt val]
       (when (= :message evt)
         (future
           (Thread/sleep 10)
           (dn :message val))))))

  (let [connect (client {:pool true})]
    (doseq [ch [ch2 ch3]]
      (connect
       (fn [dn]
         (fn [evt val]
           (enqueue ch [evt val])
           (when (= :open evt)
             (dn :message "ZOMG!"))
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
   (fn [dn]
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
       (fn [dn]
         (fn [evt val]
           (enqueue ch2 [evt val])
           (when (= :open evt)
             (dn :message (str "Zomg! " i)))))
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
   (fn [dn]
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
       (fn [dn]
         (fn [evt val]
           (when (= :abort evt)
             (enqueue ch3 [evt val])
             (.printStackTrace val))

           (when (= :open evt)
             (dn :message "ZOMG"))

           (when (= :message evt)
             (enqueue ch2 [:success nil])
             (dn :close nil))))
       {:host "localhost" :port 4040})

      (is (next-msgs ch2 :success nil))))

  (is (next-msgs ch1 :binding nil :binding nil))
  (is (no-msgs ch3)))

;; (defcoretest race-conditions-with-server-closing-connection
;;   [_ ch2 ch3]
;;   (server/start
;;    (fn [dn]
;;      (fn [evt val]
;;        (when (= :message evt)
;;          (dn :message val)
;;          (dn :close nil)))))

;;   (let [connect (client {:pool true})]
;;     (dotimes [_ 2]
;;       (connect
;;        (fn [dn]
;;          (fn [evt val]
;;            (when (= :abort evt)
;;              (enqueue ch3 [evt val])
;;              (.printStackTrace val))
;;            (when (= :open evt)
;;              (dn :message "ZOMG"))
;;            (when (= :message evt)
;;              (dn :close nil)
;;              (enqueue ch2 [:success nil])
;;              (println "!!! WIN!"))))
;;        {:host "localhost" :port 4040})

;;       (is (next-msgs ch2 :success nil))))

;;   (is (no-msgs ch3)))

;; TODO:
;; * Checking out connection sending up :open raises
