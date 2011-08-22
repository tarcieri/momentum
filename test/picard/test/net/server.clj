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

  (is (next-msgs :open nil))

  (write-socket "Hello world")
  (is (next-msgs :message "Hello world"))
  (is (receiving "Hello world"))

  (close-socket)
  (is (next-msgs :close nil)))
