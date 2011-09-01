(ns picard.test.net.server
  (:use
   clojure.test
   support.helpers
   picard.net.server))

(def addr-info
  {:local-addr  ["127.0.0.1" 4040]
   :remote-addr ["127.0.0.1" :dont-care]})

(defcoretest simple-echo-server
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :message evt)
         (dn :message val)))))

  (with-socket
    (is (next-msgs
         ch1
         :open addr-info))

    (write-socket "Hello world")
    (is (next-msgs ch1 :message "Hello world"))
    (is (receiving "Hello world"))

    (close-socket)
    (is (next-msgs ch1 :close nil))))

(defcoretest sending-multiple-packets
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val]))))

  (with-socket
    (write-socket "Hello world")
    (flush-socket)
    (Thread/sleep 50)
    (write-socket "Goodbye world")
    (close-socket)

    (is (next-msgs
         ch1
         :open    addr-info
         :message "Hello world"
         :message "Goodbye world"
         :close   nil))))

(defcoretest sending-close-event-closes-connection
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :open evt)
         (dn :message "Hello world")
         (dn :close nil)))))

  (with-socket
    (is (receiving "Hello world"))
    (Thread/sleep 50)
    (is (not (open-socket?)))
    (is (next-msgs
         ch1
         :open  addr-info
         :close nil))))

(defcoretest writing-to-closed-socket
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :open evt)
         (future
          (Thread/sleep 30)
          (dn :message "Hello"))))))

  (with-socket
    (close-socket)
    (is (next-msgs
         ch1
         :open   addr-info
         :close  nil
         :abort  #(instance? java.io.IOException %)))))

(defcoretest handling-exception-in-bind-function
  [ch1]
  (start
   (fn [dn] (throw (Exception. "TROLLOLOL"))))

  (with-socket
    (Thread/sleep 30)
    (is (not (open-socket?)))))

(defcoretest handling-exception-after-open-event
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :open evt)
         (throw (Exception. "TROLLOLOL"))))))

  (with-socket
    (is (next-msgs
         ch1
         :open  addr-info
         :abort #(instance? Exception %)))))

(defcoretest handling-exception-after-message-event
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :message evt)
         (throw (Exception. "TROLLOLOL"))))))

  (with-socket
    (write-socket "Hello world")

    (is (next-msgs
         ch1
         :open    addr-info
         :message "Hello world"
         :abort   #(instance? Exception %)))))

(defcoretest handling-exception-after-abort-event
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (#{:open :abort} evt)
         (throw (Exception. "TROLLOLOL"))))))

  (with-socket
    (write-socket "Hello world")

    (is (next-msgs
         ch1
         :open  addr-info
         :abort #(instance? Exception %)))

    (is (no-msgs ch1))))

(defcoretest abort-messages-get-prioritized-over-other-events
  [ch1 ch2]
  (start
   (fn [dn]
     (let [depth (atom 0)]
       (fn [evt val]
         (let [count (swap! depth inc)]
           (enqueue ch1 [evt val])
           (enqueue ch2 [:depth count])

           (when (= :open evt)
             (dn :close nil)
             (dn :abort (Exception. "TROLLOLOL")))
           (swap! depth dec))))))

  (with-socket
    (is (next-msgs
         ch1
         :open  addr-info
         :abort #(instance? Exception %)))

    (is (next-msgs
         ch2
         :depth 1
         :depth 1))))

(defcoretest thrown-exceptions-get-prioritized-over-other-events
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :message evt)
         (dn :close nil)
         (throw (Exception. "LULZ"))))))

  (with-socket
    (write-socket "Hello world")

    (is (next-msgs
         ch1
         :open    addr-info
         :message "Hello world"
         :abort   #(instance? Exception %)))))

(defcoretest telling-the-application-to-chill-out
  [ch1]
  (start
   (fn [dn]
     (let [latch (atom true)]
       (fn [evt val]
         (enqueue ch1 [evt val])
         (when (= :open evt)
           (future
             (loop [continue? @latch]
               (if continue?
                 (do
                   (dn :message "HAMMER TIME!")
                   (recur @latch))
                 (do
                   (Thread/sleep 100)
                   (dn :close nil))))))
         (when (= :pause evt)
           (reset! latch false))))))

  (with-socket
    (Thread/sleep 400)
    (drain-socket)

    (is (next-msgs
         ch1
         :open   addr-info
         :pause  nil
         :resume nil
         :close  nil))))

(defcoretest raising-error-during-pause-event
  [ch1]
  (start
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
           (throw (Exception. "TROLLOLOL")))))))

  (with-socket
    (Thread/sleep 100)
    (drain-socket)

    (is (not (open-socket?)))

    (is (next-msgs
         ch1
         :open   addr-info
         :pause  nil
         :abort  #(instance? Exception %)))))

(defcoretest raising-error-during-resume-event
  [ch1]
  (start
   (fn [dn]
     (let [latch (atom true)]
       (fn [evt val]
         (enqueue ch1 [evt val])
         (when (= :open evt)
           (future
             (loop [continue? @latch]
               (if continue?
                 (do
                   (dn :message "HAMMER TIME!")
                   (recur @latch))))))

         (when (= :pause evt)
           (reset! latch false))

         (when (= :resume evt)
           (throw (Exception. "TROLLOLOL")))))))

  (with-socket
    (Thread/sleep 100)
    (drain-socket)

    (is (not (open-socket?)))

    (is (next-msgs
         ch1
         :open   addr-info
         :pause  nil
         :resume nil
         :abort  #(instance? Exception %)))))

(defcoretest telling-the-server-to-chill-out
  [ch1 ch2]
  (start
   (fn [dn]
     (receive ch2 (fn [_] (dn :resume nil)))
     (let [latch (atom true)]
       (fn [evt val]
         (when-not (#{:pause :resume} evt)
           (enqueue ch1 [evt val]))
         (when (and (= :message evt) @latch)
           (dn :pause nil)
           (reset! latch false))))))

  (with-socket
    (write-socket "Hello world")
    (flush-socket)

    (Thread/sleep 50)

    (write-socket "Goodbye world")
    (close-socket)

    (is (next-msgs
         ch1
         :open    addr-info
         :message "Hello world"))

    (enqueue ch2 :resume)

    (is (next-msgs
         ch1
         :message "Goodbye world"
         :close   nil))))

(defcoretest avoiding-abort-loops
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (dn :abort (Exception. "TROLLOLOL")))))

  (with-socket
    (is (next-msgs
         ch1
         :open  addr-info
         :abort #(instance? Exception %)))

    (is (no-msgs ch1))))

(defcoretest throws-exception-when-receiving-unknown-event
  [ch1]
  (start
   (fn [dn]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :open evt)
         (dn :zomg 1)))))

  (with-socket
    (is (next-msgs
         ch1
         :open  addr-info
         :abort #(instance? Exception %)))))
