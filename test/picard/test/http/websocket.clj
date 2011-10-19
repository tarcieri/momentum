(ns picard.test.http.websocket
  (:require
   [picard.http.websocket :as ws])
  (:use
   clojure.test
   picard.http.test
   picard.core.buffer
   picard.utils.core)
  (:require
   [picard.utils.base64 :as base64]
   [picard.utils.digest :as digest]
   [picard.utils.random :as random]))

(deftest exchanges-without-upgrade-header-are-ignored
  (with-app
    (ws/proto
     (fn [dn]
       (defstream
         (request [_]
           (dn :response [200 {"content-length" "5"} (buffer "Hello")])))))

    (GET "/")
    (is (responded? [200 {"content-length" "5"} "Hello"]))))

(deftest simple-handshake
  (with-app
    (ws/proto
     (fn [dn]
       (defstream
         (request [hdrs body]
           (dn :response [101 {} :upgraded]))
         (abort [err]
           (.printStackTrace err))
         (else [evt val]
           (println "GOT: " [evt val])))))

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
        :ubyte (bit-or 0x80 1) (bit-or 0x80 5)
        :uint  0x6043cee3
        :ubyte 0x28 0x26 0xa2 0x8f 0x0f)))))

;; Aborts the socket when key not set
