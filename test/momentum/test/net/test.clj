(ns momentum.test.net.test
  (:use
   clojure.test
   support.assertions
   momentum.core
   momentum.net.test))

(deftest simple-ping-pong
  (let [ch (channel)]
    (with-endpoint
      (fn [dn _]
        (fn [evt val]
          (put ch [evt val])
          (dn :message "pong")
          (dn :close nil)))

      (let [conn (open)]
        (conn :message "ping")
        (Thread/sleep 20)))

    (close ch)
    (is (msg-match
         ch
         :open    {:remote-addr :dont-care :local-addr :dont-care}
         :message "ping"
         :close   nil))))

(deftest closing-connection-from-client
  (let [ch (channel)]
    (with-endpoint
      (fn [dn _]
        (fn [evt val]
          (put ch [evt val])
          (when (= :message evt)
            (dn :message val))))

      (let [conn (open)]
        (conn :message "hi")
        (conn :close nil))

      (Thread/sleep 20)
      (close ch)

      (is (msg-match
           ch
           :open    :dont-care
           :message "hi"
           :close   nil)))))

(deftest pause-resume-from-server
  (let [ch (channel)]
    (with-endpoint
      (fn [dn _]
        (fn [evt val]
          (put ch [evt val])
          (when (= :open evt)
            (dn :pause nil)
            (future
              (Thread/sleep 150)
              (dn :resume nil)))))

      (let [conn (open)]
        (future
          (conn :message "hello")
          (conn :message "world")))

      (is (next-msgs ch :open :dont-care))
      (is (no-msgs ch))
      (is (next-msgs ch :message "hello" :message "world")))))

(deftest handling-upstream-abort-events
  (with-endpoint
    (fn [dn _]
      (fn [evt val]
        (when (= :open evt)
          (dn :abort (Exception. "BOOM")))))

    (let [conn (open)]
      (is (= :open (ffirst conn)))
      (is (= [:close nil] (second conn))))))

(deftest handling-upstream-exceptions
  (with-endpoint
    (fn [dn _]
      (fn [evt val]
        (when (= :open evt)
          (throw (Exception. "BOOM")))))

    (let [conn (open)]
      (is (= [:close nil] (second conn))))))

;; TODO:
;; * Upstream / downstream :abort
