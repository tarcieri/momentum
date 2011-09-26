(ns picard.test.http.parser
  (:use
   clojure.test
   support.parsing
   picard.http.parser)
  (:require
   [clojure.string :as str])
  (:import
   [picard.http
    HttpParserException]))

(def valid-uris
  {"/"
   {:path-info "/" :query-string ""}

   "/hello/world"
   {:path-info "/hello/world" :query-string ""}

   "g:h"
   {:path-info "" :query-string ""}

   "/forums/1/topics/2375?page=1#posts-17408"
   {:path-info "/forums/1/topics/2375" :query-string "page=1"}

   "/test.cgi?foo=bar?baz"
   {:path-info "/test.cgi" :query-string "foo=bar?baz"}

   "/with_\"stupid\"_quotes?foo=\"bar\""
   {:path-info "/with_\"stupid\"_quotes" :query-string "foo=\"bar\""}})


(def get-request  {:request-method "GET"  :path-info "/" :query-string "" :http-version [1 1]})
(def post-request {:request-method "POST" :path-info "/" :query-string "" :http-version [1 1]})

;; ==== REQUEST LINE TESTS

(deftest parsing-normal-request-lines
  (doseq [method valid-methods]
    (is (parsed-as
         (str method " / HTTP/1.1\r\n\r\n")
         :request [{:request-method method
                    :path-info      "/"
                    :query-string   ""
                    :http-version   [1 1]} nil])))

  (is (parsed-as
       "GET / HTTP/1.0\r\n\r\n"
       :request [(assoc get-request :http-version [1 0]) nil]))

  (is (parsed-as
       "GET / HTTP/0.9\r\n\r\n"
       :request [{:request-method "GET"
                  :path-info      "/"
                  :query-string   ""
                  :http-version   [0 9]} nil]))

  (is (parsed-as
       "GET / HTTP/90.23\r\n\r\n"
       :request [{:request-method "GET"
                  :path-info      "/"
                  :query-string   ""
                  :http-version   [90 23]} nil])))

(deftest parsing-normal-request-lines-in-chunks
  (doseq [method valid-methods]
    (is (parsed-as
         (str/split (str method " / HTTP/1.1\r\n\r\n") #"")
         :request [{:request-method method
                    :path-info      "/"
                    :query-string   ""
                    :http-version   [1 1]} nil]))))

(deftest parsing-some-request-line-edge-cases
  (is (parsed-as
       "GET  / HTTP/1.1\r\n\r\n"
       :request [get-request nil]))

  (is (parsed-as
       "GET /  HTTP/1.1\r\n\r\n"
       :request [get-request nil]))

  (is (parsed-as
       "GET / Http/1.1\r\n\r\n"
       :request [get-request nil]))

  (is (parsed-as
       "GET / HTTP/1.1\r\n\r\n"
       :request [get-request nil]))

  (is (parsed-as
       "\r\nGET / HTTP/1.1\r\n\r\n"
       :request [get-request nil])))

(deftest parsing-various-valid-request-uris
  (doseq [[uri hdrs] valid-uris]
    (let [raw (str "GET " uri " HTTP/1.1\r\n\r\n")]
      (is (parsed-as
           raw
           :request [(merge hdrs {:request-method "GET" :http-version [1 1]}) nil]))

      (is (parsed-as
           (str/split raw #"")
           :request [(merge hdrs {:request-method "GET" :http-version [1 1]}) nil]))))

  (is (parsed-as
       ["GET /hello" "/world HTTP/1.1\r\n\r\n"]
       :request [{:request-method "GET"
                  :http-version   [1 1]
                  :path-info      "/hello/world"
                  :query-string   ""} nil]))

  (is (parsed-as
       ["GET /hello/world?zomg" "whatsup HTTP/1.1\r\n\r\n"]
       :request [{:request-method "GET"
                  :http-version   [1 1]
                  :path-info      "/hello/world"
                  :query-string   "zomgwhatsup"} nil])))

(deftest parsing-single-headers
  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Zomg: HI2U\r\n\r\n")
       :request [(assoc get-request "zomg" "HI2U") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Zomg:HI2U\r\n\r\n")
       :request [(assoc get-request "zomg" "HI2U") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Zomg  :  HI2U \r\n\r\n")
       :request [(assoc get-request "zomg" "HI2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "Zomg" ": HI2U\r\n\r\n"]
       :request [(assoc get-request "zomg" "HI2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "ZO" "MG" "  : " "HI2U\r\n\r\n"]
       :request [(assoc get-request "zomg" "HI2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: " "H" "I" "2" "U" "\r\n\r\n"]
       :request [(assoc get-request "zomg" "HI2U") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "zomg: HI  2U \r\n\r\n")
       :request [(assoc get-request "zomg" "HI  2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: HI  " "2U\r\n\r\n"]
       :request [(assoc get-request "zomg" "HI  2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: HI " " " " 2U\r\n\r\n"]
       :request [(assoc get-request "zomg" "HI   2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: HI2U" "\r\n\r\n"]
       :request [(assoc get-request "zomg" "HI2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: HI" " " " 2U\r\n\r\n"]
       :request [(assoc get-request "zomg" "HI  2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: HI " " 2U" "\r\n\r\n"]
       :request [(assoc get-request "zomg" "HI  2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: HI" " " "\r\n\r\n"]
       :request [(assoc get-request "zomg" "HI") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: HI " " " " \r\n\r\n"]
       :request [(assoc get-request "zomg" "HI") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "zomg: HI\r\n"
            " 2U\r\n\r\n")
       :request [(assoc get-request "zomg" "HI 2U") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "zomg: HI\r\n"
            "\t2U\r\n\r\n")
       :request [(assoc get-request "zomg" "HI 2U") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "zomg:HI\r\n"
            " 2U\r\n\r\n")
       :request [(assoc get-request "zomg" "HI 2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: HI " "\r\n 2U\r\n\r\n"]
       :request [(assoc get-request "zomg" "HI 2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "Zomg: HI  " "\r\n"
        "  2U\r\n\r\n"]
       :request [(assoc get-request "zomg" "HI 2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "Zomg: HI   \r\n"
        "   2U\r\n\r\n"]
       :request [(assoc get-request "zomg" "HI 2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "Zomg:\r\n\r\n"]
       :request [(assoc get-request "zomg" "") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "Zomg:" "\r\n\r\n"]
       :request [(assoc get-request "zomg" "") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "ZOMG" "  " " : " "      " "\r\n\r\n"]
       :request [(assoc get-request "zomg" "") nil])))

(def header-to-test-val
  {"content-length" "1000"})

(deftest parsing-standard-headers
  (doseq [expected standard-headers]
    (let [[[_ [hdrs]]]
          (parsing
           (str "GET / HTTP/1.1\r\n"
                expected ": " (header-to-test-val expected "Zomg")
                "\r\n\r\n"))

          actual
          (->> hdrs keys (filter #(= expected %)) first)]

      (is (identical? expected actual)))))

(deftest parsing-special-case-headers
  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Connection:  CLOSE\r\n\r\n")
       :request [(assoc get-request "connection" "close") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Transfer-Encoding: CHUNKED\r\n\r\n")
       :request [(assoc get-request "transfer-encoding" "chunked") :chunked])))

(deftest parsing-content-lengths
  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Content-Length: 1000\r\n\r\n")
       :request [(assoc get-request "content-length" "1000") :chunked]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Content-Length: 922337203685477580\r\n\r\n")
       :request [(assoc get-request "content-length" "922337203685477580") :chunked]))

  (is (thrown?
       HttpParserException
       (parsing (str "GET / HTTP/1.1\r\n"
                     "Content-Length: 9223372036854775807\r\n\r\n"))))
  (is (thrown?
       HttpParserException
       (parsing (str "GET / HTTP/1.1\r\n"
                     "Content-Length: lulz\r\n\r\n"))))

  (is (thrown?
       HttpParserException
       (parsing (str "GET / HTTP/1.1\r\n"
                     "content-length: 12lulz0\r\n\r\n"))))

  (is (thrown?
       HttpParserException
       (parsing (str "GET / HTTP/1.1\r\n"
                     "content-length: 1234l\r\n\r\n")))))

(deftest parsing-transfer-encodings
  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Transfer-Encoding: chunked\r\n\r\n")
       :request [(assoc get-request "transfer-encoding" "chunked") :chunked]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "transfer-encoding: chunked \r\n\r\n")
       :request [(assoc get-request "transfer-encoding" "chunked") :chunked]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Transfer-Encoding: chunked;lulz\r\n\r\n")
       :request [(assoc get-request "transfer-encoding" "chunked;lulz") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Transfer-encoding: Chunked\r\n\r\n")
       :request [(assoc get-request "transfer-encoding" "chunked") :chunked]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Transfer-encoding: chun\r\n\r\n")
       :request [(assoc get-request "transfer-encoding" "chun") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "TRANSFER-ENCODING: zomg\r\n\r\n")
       :request [(assoc get-request "transfer-encoding" "zomg") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Transfer-Encoding: zomg \r\n\r\n")
       :request [(assoc get-request "transfer-encoding" "zomg") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "transfer-encoding: zomg;omg\r\n\r\n")
       :request [(assoc get-request "transfer-encoding" "zomg;omg") nil])))

(deftest parsing-identity-bodies
  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Content-Length: 5\r\n"
            "\r\n"
            "Hello")
       :request [(assoc get-request "content-length" "5") "Hello"]))

  (is (parsed-as
       [(str "GET / HTTP/1.1\r\n"
             "Content-Length: 5\r\n\r\n")
        "Hello"]
       :request [(assoc get-request "content-length" "5") :chunked]
       :body    "Hello"
       :body    nil))

  (is (parsed-as
       [(str "GET / HTTP/1.1\r\n"
             "Content-Length: 10\r\n\r\n")
        "Hello" "World"]
       :request [(assoc get-request "content-length" "10") :chunked]
       :body    "Hello"
       :body    "World"
       :body    nil))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "CONTENT-LENGTH: 11\r\n\r\nHello World")
       :request [(assoc get-request "content-length" "11") "Hello World"])))

(deftest parsing-chunked-bodies
  (is (parsed-as
       (str "POST / HTTP/1.1\r\n"
            "Transfer-Encoding: chunked\r\n\r\n"
            "5\r\nHello\r\n"
            "5\r\nWorld\r\n"
            "0\r\n\r\n")
       :request [(assoc post-request "transfer-encoding" "chunked") :chunked]
       :body    "Hello"
       :body    "World"
       :body    nil))

  (is (parsed-as
       ["POST / HTTP/1.1\r" "\n"
        "TRANSFER-encoding :chunked" "\r\n\r\n"
        "00" "5" "\r\n"
        "H" "ello\r\n"
        "A" "\r\n"
        "aaaaaaaaaa" "\r\n"
        "b" "\r\n"
        "bbbbbbbbbbb\r\n"
        "10\r\n" "9999999999999999\r\n"
        "00" "\r\n\r\n"]
       :request [(assoc post-request "transfer-encoding" "chunked") :chunked]
       :body    "H"
       :body    "ello"
       :body    "aaaaaaaaaa"
       :body    "bbbbbbbbbbb"
       :body    "9999999999999999"
       :body    nil))

  (is (parsed-as
       (str "POST / HTTP/1.1\r\n"
            "transfer-encoding: chunked\r\n"
            "\r\n"
            "5;lol=fail\r\n"
            "Hello\r\n"
            "0\r\n"
            "Content-md5: zomg\r\n"
            "\r\n")
       :request [(assoc post-request "transfer-encoding" "chunked") :chunked]
       :body    "Hello"
       :body    nil)))

(deftest parsing-upgraded-connections
  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Connection: Upgrade\r\n\r\n"
            "ZOMGHI2U\r\n")
       :request [(assoc get-request "connection" "upgrade") nil]
       :message "ZOMGHI2U\r\n"))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "Connection: UPGRADE\r\n\r\n"
        "GET / HTTP/1.1\r\n\r\n"]
       :request [(assoc get-request "connection" "upgrade") nil]
       :message "GET / HTTP/1.1\r\n\r\n"))

  (is (parsed-as
       (str "CONNECT / HTTP/1.1\r\n\r\n"
            "Hello world")
       :request [(assoc get-request :request-method "CONNECT") nil]
       :message "Hello world"))

  (is (parsed-as
       (str "GET /demo HTTP/1.1\r\n"
            "Host: example.com\r\n"
            "Connection: Upgrade\r\n"
            "Sec-WebSocket-Key2: 12998 5 Y3 1  .P00\r\n"
            "Sec-WebSocket-Protocol: sample\r\n"
            "Upgrade: WebSocket\r\n"
            "Sec-WebSocket-Key-1: 4 @1  46546xW%01 1 5\r\n"
            "Origin: http://example.com\r\n"
            "\r\n"
            "Hot diggity dogg")
       :request [(assoc get-request
                   :path-info "/demo"
                   "host" "example.com"
                   "connection" "upgrade"
                   "sec-websocket-key2" "12998 5 Y3 1  .P00"
                   "sec-websocket-protocol" "sample"
                   "upgrade" "WebSocket"
                   "sec-websocket-key-1" "4 @1  46546xW%01 1 5"
                   "origin" "http://example.com") nil]
       :message "Hot diggity dogg")))

(deftest keepalive-requests
  (is (parsed-as
       (str "GET / HTTP/1.1\r\n\r\n"
            "GET / HTTP/1.1\r\n\r\n")
       :request [get-request nil]
       :request [get-request nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n\r\n"
        "GET / HTTP/1.1\r\n\r\n"]
       :request [get-request nil]
       :request [get-request nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Host: localhost\r\n"
            "Foo: 123\r\n\r\n"
            "POST / HTTP/1.1\r\n"
            "Content-Length: 11\r\n\r\n"
            "Hello world"
            "GET / HTTP/1.1\r\n\r\n")
       :request [(assoc get-request "host" "localhost" "foo" "123") nil]
       :request [(assoc post-request "content-length" "11") "Hello world"]
       :request [get-request nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "Host: localhost\r\n\r\n"
        "POST / HTTP/1.1\r\n"
        "Content-Length: 11\r\n\r\n"
        "Hello " "world"
        "POST / HTTP/1.1\r\n"
        "CONTENT-LENGTH: 11\r\n\r\nHello world"
        "GET / HTTP/1.1\r\n\r\n"]
       :request [(assoc get-request "host" "localhost") nil]
       :request [(assoc post-request "content-length" "11") :chunked]
       :body    "Hello "
       :body    "world"
       :body    nil
       :request [(assoc post-request "content-length" "11") "Hello world"]
       :request [get-request nil])))

(deftest insanity-requests
  (is (thrown?
       HttpParserException
       (parsing (repeat "\r\n"))))

  (is (thrown?
       HttpParserException
       (parsing
        (str "GET / HTTP/1.1\r\n"
             (apply str (repeat 102400 "a")) ":X\r\n\r\n"))))

  (is (thrown?
       HttpParserException
       (parsing
        (concat
         ["GET / HTTP/1.1\r\n"]
         (repeat 102400 "a") ["X\r\n\r\n"]))))

  (is (thrown?
       HttpParserException
       (parsing
        (concat
         ["GET / HTTP/1.1\r\n"]
         (repeat "Zomg: HI2U\r\n"))))))
