(ns picard.test.http.websocket
  (:require
   [picard.http.websocket :as ws])
  (:use
   clojure.test
   support.helpers
   picard.core.buffer
   picard.http.server)
  (:require
   [picard.utils.base64 :as base64]
   [picard.utils.digest :as digest]
   [picard.utils.random :as random]))

(defcoretest exchanges-without-upgrade-header-are-ignored
  [ch1]
  (start
   (ws/proto
    (fn [dn]
      (fn [evt val]
        (enqueue ch1 [evt val])
        (when (= :request evt)
          (dn :response [200 {"content-length" "5"} (buffer "Hello")]))))))

  (with-socket
    (write-socket
     "GET / HTTP/1.1\r\n"
     "Host: localhost\r\n"
     "\r\n")

    (is (next-msgs
         ch1
         :request [#(includes-hdrs {"host" "localhost"} %) nil]
         :done    nil))

    (is (receiving
         "HTTP/1.1 200 OK\r\n"
         "content-length: 5\r\n\r\n"
         "Hello"))))

;; (defcoretest simple-handshake
;;   [ch1]
;;   (start
;;    (ws/proto
;;     (fn [dn]
;;       (fn [evt val]
;;         (when (= :abort evt)
;;           (.printStackTrace val))
;;         (enqueue ch1 [evt val])
;;         (when (= :request evt)
;;           (dn :response [200 {"content-length" "5"} "Hello"]))))))

;;   (with-socket
;;     (write-socket
;;      "GET / HTTP/1.1\r\n"
;;      "Host: localhost\r\n"
;;      "Upgrade: websocket\r\n"
;;      "Connection: upgrade\r\n"
;;      "Sec-WebSocket-Key: " (base64/encode (random/secure-random 16)) "\r\n"
;;      "Sec-WebSocket-Origin: http://localhost\r\n"
;;      "Sec-WebSocket-Version: 8\r\n"
;;      "\r\n")

;;     ;; (is (next-msgs
;;     ;;      ch1
;;     ;;      :request
;;     ;;      :done nil))

;;     ;; (is (no-msgs ch1))

;;     (is (receiving
;;          "HTTP/1.1 200 OK\r\n"
;;          "content-length: 5\r\n\r\n"
;;          "Hello"))))
