(ns picard.test.http.websocket
  (:require
   [picard.http.websocket :as ws])
  (:use
   clojure.test
   picard.core
   picard.http.test
   picard.utils.core)
  (:require
   [picard.utils.base64 :as base64]
   [picard.utils.digest :as digest]
   [picard.utils.random :as random]))

(defmacro with-ws-app
  [app & stmts]
  `(with-app (ws/proto ~app) ~@stmts))

(deftest exchanges-without-upgrade-header-are-ignored
  (with-app
    (ws/proto
     (fn [dn]
       (defstream
         (request [_]
           (dn :response [200 {"content-length" "5"} (buffer "Hello")])))))

    (GET "/")
    (is (responded? [200 {"content-length" "5"} "Hello"]))))

(deftest simple-websocket-exchange
  (let [ch (blocking-channel 100)]
    (with-ws-app
      (fn [dn]
        (defstream
          (request [hdrs body]
            (dn :response [101 {} :upgraded]))
          (abort [err]
            (.printStackTrace err))
          (message [msg]
            (enqueue ch [:message msg])
            (dn :message "roger"))))

      (let [ws-key (base64/encode (random/secure-random 16))
            expect (base64/encode (digest/sha1 (str ws-key ws/salt)))]

        (GET "/" {"upgrade"              "websocket"
                  "connection"           "upgrade"
                  "sec-websocket-key"    ws-key
                  "sec-websocket-origin" "http://localhost"} :upgraded)

        (is (responded?
             [101 {"connection" "upgrade"
                   "upgrade"    "websocket"
                   "sec-websocket-accept" expect} :upgraded]))

        (last-request
         :message
         (buffer
          :ubyte
          (bit-or 0x80 1)  ;; FIN + OP TEXT
          (bit-or 0x80 5)  ;; Masked + LEN 5
          :uint  0x6043cee3
          :ubyte 0x28 0x26 0xa2 0x8f 0x0f))

        (is (= (list [:message "Hello"])
               (take 1 (seq ch))))

        (is (received?
             :message
             (buffer
              :ubyte
              (bit-or 0x80 1) ;; FIN + OP TEXT
              5               ;; Len 5 (no mask)
              "roger")))))))

(deftest aborts-socket-when-key-not-set
  (let [ch (channel)]
    (with-ws-app
      (fn [dn]
        (fn [evt val]
          (put ch [evt val])))

      (GET "/" {"upgrade"    "websocket"
                "connection" "upgrade"
                "sec-websocket-origin" "http://localhost"} :upgraded)

      (is (closed?)))))
