(ns picard.test.http.parser
  (:use
   clojure.test
   support.parsing
   picard.http.parser)
  (:require
   [clojure.string :as str])
  (:import
   [picard.http
    HttpParserException]
   [java.util.concurrent
    LinkedBlockingQueue]))

(def valid-uris
  {"/"
   {:path-info "/" :query-string ""}

   "/hello/world"
   {:path-info "/hello/world" :query-string ""}

   "g:h"
   {:path-info "/" :query-string ""}

   "/forums/1/topics/2375?page=1#posts-17408"
   {:path-info "/forums/1/topics/2375" :query-string "page=1"}

   "/test.cgi?foo=bar?baz"
   {:path-info "/test.cgi" :query-string "foo=bar?baz"}

   ;; "/with_\"stupid\"_quotes?foo=\"bar\""
   ;; {:path-info "/with_\"stupid\"_quotes" :query-string "foo=\"bar\""}

   ;; "GET /δ¶/δt/pope?q=1#narf HTTP/1.1\r\n"
   ;; {:path-info "/δ¶/δt/pope" :query-string "q=1"}

   "http://hypnotoad.org/hail?all"
   {:path-info "/hail" :query-string "all"}

   "http://hypnotoad.org?hail=all"
   {:path-info "/" :query-string "hail=all"}

   "http://hypnotoad.org:1234/"
   {:path-info "/" :query-string ""}

   "http://hypnotoad.org:1234"
   {:path-info "/" :query-string ""}})


(def get-request  {:request-method "GET"  :path-info "/"
                   :script-name    ""     :query-string ""
                   :http-version [1 1]})
(def post-request (assoc get-request :request-method "POST"))

;; ==== REQUEST LINE TESTS

(deftest parsing-normal-request-lines
  (doseq [method valid-methods]
    (is (parsed
         (str method " / HTTP/1.1\r\n\r\n")
         :request [(assoc get-request :request-method method)
                   (when (= "CONNECT" method)
                     :upgraded)])))

  (is (parsed
       "GET / HTTP/1.0\r\n\r\n"
       :request [(assoc get-request :http-version [1 0]) nil]))

  (is (parsed
       "GET / HTTP/0.9\r\n\r\n"
       :request [(assoc get-request :http-version [0 9]) nil]))

  (is (parsed
       "GET / HTTP/90.23\r\n\r\n"
       :request [(assoc get-request :http-version [90 23]) nil])))

(deftest parsing-normal-request-lines-in-chunks
  (doseq [method valid-methods]
    (is (parsed
         (str/split (str method " / HTTP/1.1\r\n\r\n") #"")
         :request [(assoc get-request :request-method method)
                   (when (= "CONNECT" method)
                     :upgraded)]))))

(deftest parsing-some-request-line-edge-cases
  (is (parsed
       "GET  / HTTP/1.1\r\n\r\n"
       :request [get-request nil]))

  (is (parsed
       "GET /  HTTP/1.1\r\n\r\n"
       :request [get-request nil]))

  (is (parsed
       "GET / Http/1.1\r\n\r\n"
       :request [get-request nil]))

  (is (parsed
       "GET / HTTP/1.1\r\n\r\n"
       :request [get-request nil]))

  (is (parsed
       "\r\nGET / HTTP/1.1\r\n\r\n"
       :request [get-request nil]))

  (is (parsed
       "GET /\r\n\r\n"
       :request [(assoc get-request :http-version [0 9]) nil])))

(deftest parsing-various-valid-request-uris
  (doseq [[uri hdrs] valid-uris]
    (let [raw (str "GET " uri " HTTP/1.1\r\n\r\n")]
      (is (parsed
           raw
           :request [(merge get-request hdrs) nil]))

      (is (parsed
           (str/split raw #"" 14)
           :request [(merge get-request hdrs) nil]))))

  (is (parsed
       ["GET /hello" "/world HTTP/1.1\r\n\r\n"]
       :request [(assoc get-request :path-info "/hello/world") nil]))

  (is (parsed
       ["GET /hello/world?zomg" "whatsup HTTP/1.1\r\n\r\n"]
       :request [(assoc get-request
                   :path-info    "/hello/world"
                   :query-string "zomgwhatsup") nil])))

(deftest parsing-single-headers
  (is (parsed
       (str "GET / HTTP/1.1\r\n"
            "Zomg: HI2U\r\n\r\n")
       :request [(assoc get-request "zomg" "HI2U") nil]))

  (is (parsed
       (str "GET / HTTP/1.1\r\n"
            "Zomg:HI2U\r\n\r\n")
       :request [(assoc get-request "zomg" "HI2U") nil]))

  (is (parsed
       (str "GET / HTTP/1.1\r\n"
            "Zomg  :  HI2U \r\n\r\n")
       :request [(assoc get-request "zomg" "HI2U") nil]))

  (is (parsed
       ["GET / HTTP/1.1\r\n"
        "Zomg" ": HI2U\r\n\r\n"]
       :request [(assoc get-request "zomg" "HI2U") nil]))

  (is (parsed
       ["GET / HTTP/1.1\r\n"
        "ZO" "MG" "  : " "HI2U\r\n\r\n"]
       :request [(assoc get-request "zomg" "HI2U") nil]))

  (is (parsed
       ["GET / HTTP/1.1\r\n"
        "zomg: " "H" "I" "2" "U" "\r\n\r\n"]
       :request [(assoc get-request "zomg" "HI2U") nil]))

  (is (parsed
       (str "GET / HTTP/1.1\r\n"
            "zomg: HI  2U \r\n\r\n")
       :request [(assoc get-request "zomg" "HI  2U") nil]))

  (is (parsed
       ["GET / HTTP/1.1\r\n"
        "zomg: HI  " "2U\r\n\r\n"]
       :request [(assoc get-request "zomg" "HI  2U") nil]))

  (is (parsed
       ["GET / HTTP/1.1\r\n"
        "zomg: HI " " " " 2U\r\n\r\n"]
       :request [(assoc get-request "zomg" "HI   2U") nil]))

  (is (parsed
       ["GET / HTTP/1.1\r\n"
        "zomg: HI2U" "\r\n\r\n"]
       :request [(assoc get-request "zomg" "HI2U") nil]))

  (is (parsed
       ["GET / HTTP/1.1\r\n"
        "zomg: HI" " " " 2U\r\n\r\n"]
       :request [(assoc get-request "zomg" "HI  2U") nil]))

  (is (parsed
       ["GET / HTTP/1.1\r\n"
        "zomg: HI " " 2U" "\r\n\r\n"]
       :request [(assoc get-request "zomg" "HI  2U") nil]))

  (is (parsed
       ["GET / HTTP/1.1\r\n"
        "zomg: HI" " " "\r\n\r\n"]
       :request [(assoc get-request "zomg" "HI") nil]))

  (is (parsed
       ["GET / HTTP/1.1\r\n"
        "zomg: HI " " " " \r\n\r\n"]
       :request [(assoc get-request "zomg" "HI") nil]))

  (is (parsed
       (str "GET / HTTP/1.1\r\n"
            "zomg: HI\r\n"
            " 2U\r\n\r\n")
       :request [(assoc get-request "zomg" "HI 2U") nil]))

  (is (parsed
       (str "GET / HTTP/1.1\r\n"
            "zomg: HI\r\n"
            "\t2U\r\n\r\n")
       :request [(assoc get-request "zomg" "HI 2U") nil]))

  (is (parsed
       (str "GET / HTTP/1.1\r\n"
            "zomg:HI\r\n"
            " 2U\r\n\r\n")
       :request [(assoc get-request "zomg" "HI 2U") nil]))

  (is (parsed
       ["GET / HTTP/1.1\r\n"
        "zomg: HI " "\r\n 2U\r\n\r\n"]
       :request [(assoc get-request "zomg" "HI 2U") nil]))

  (is (parsed
       ["GET / HTTP/1.1\r\n"
        "Zomg: HI  " "\r\n"
        "  2U\r\n\r\n"]
       :request [(assoc get-request "zomg" "HI 2U") nil]))

  (is (parsed
       ["GET / HTTP/1.1\r\n"
        "Zomg: HI   \r\n"
        "   2U\r\n\r\n"]
       :request [(assoc get-request "zomg" "HI 2U") nil]))

  (is (parsed
       ["GET / HTTP/1.1\r\n"
        "Zomg:\r\n\r\n"]
       :request [(assoc get-request "zomg" "") nil]))

  (is (parsed
       ["GET / HTTP/1.1\r\n"
        "Zomg:" "\r\n\r\n"]
       :request [(assoc get-request "zomg" "") nil]))

  (is (parsed
       ["GET / HTTP/1.1\r\n"
        "ZOMG" "  " " : " "      " "\r\n\r\n"]
       :request [(assoc get-request "zomg" "") nil]))

  (is (parsed
       (str "GET / HTTP/1.1\r\n"
            "Zomg: \"ssdp:discover\"\r\n"
            "\r\n")
       :request [(assoc get-request "zomg" "\"ssdp:discover\"") nil])))

(def header-to-test-val
  {"content-length" "1000"})

(deftest parsing-standard-headers
  (doseq [expected (disj standard-headers "content-length")]
    (let [[[_ [hdrs]]]
          (parsing
           (str "GET / HTTP/1.1\r\n"
                expected ": " (header-to-test-val expected "Zomg")
                "\r\n\r\n"))

          actual
          (->> hdrs keys (filter #(= expected %)) first)]

      (is (identical? expected actual)))))

(deftest parsing-special-case-headers
  (is (parsed
       (str "GET / HTTP/1.1\r\n"
            "Connection:  CLOSE\r\n\r\n")
       :request [(assoc get-request "connection" "close") nil]))

  (is (parsed
       (str "GET / HTTP/1.1\r\n"
            "Transfer-Encoding: CHUNKED\r\n\r\n")
       :request [(assoc get-request "transfer-encoding" "chunked") :chunked])))

(deftest parsing-content-lengths
  (is (parsed
       (str "GET / HTTP/1.1\r\n"
            "Content-Length: 10000\r\n\r\n")
       :request [(assoc get-request "content-length" "10000") :chunked]))

  (is (parsed
       (str "GET / HTTP/1.1\r\n"
            "Content-Length: 922337203685477580\r\n\r\n")
       :request [(assoc get-request "content-length" "922337203685477580") :chunked]))

  (is (thrown?
       HttpParserException
       (parsing (str "GET / HTTP/1.1\r\n"
                     "Content-Length: 92233720368547758070\r\n\r\n"))))
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
  (is (parsed
       (str "GET / HTTP/1.1\r\n"
            "Transfer-Encoding: chunked\r\n\r\n")
       :request [(assoc get-request "transfer-encoding" "chunked") :chunked]))

  (is (parsed
       (str "GET / HTTP/1.1\r\n"
            "transfer-encoding: chunked \r\n\r\n")
       :request [(assoc get-request "transfer-encoding" "chunked") :chunked]))

  (is (parsed
       (str "GET / HTTP/1.1\r\n"
            "Transfer-Encoding: chunked;lulz\r\n\r\n")
       :request [(assoc get-request "transfer-encoding" "chunked;lulz") nil]))

  (is (parsed
       (str "GET / HTTP/1.1\r\n"
            "Transfer-encoding: Chunked\r\n\r\n")
       :request [(assoc get-request "transfer-encoding" "chunked") :chunked]))

  (is (parsed
       (str "GET / HTTP/1.1\r\n"
            "Transfer-encoding: chun\r\n\r\n")
       :request [(assoc get-request "transfer-encoding" "chun") nil]))

  (is (parsed
       (str "GET / HTTP/1.1\r\n"
            "TRANSFER-ENCODING: zomg\r\n\r\n")
       :request [(assoc get-request "transfer-encoding" "zomg") nil]))

  (is (parsed
       (str "GET / HTTP/1.1\r\n"
            "Transfer-Encoding: zomg \r\n\r\n")
       :request [(assoc get-request "transfer-encoding" "zomg") nil]))

  (is (parsed
       (str "GET / HTTP/1.1\r\n"
            "transfer-encoding: zomg;omg\r\n\r\n")
       :request [(assoc get-request "transfer-encoding" "zomg;omg") nil])))

(deftest parsing-identity-bodies
  (is (parsed
       (str "GET / HTTP/1.1\r\n"
            "Content-Length: 5\r\n"
            "\r\n"
            "Hello")
       :request [(assoc get-request "content-length" "5") "Hello"]))

  (is (parsed
       [(str "GET / HTTP/1.1\r\n"
             "Content-Length: 5\r\n\r\n")
        "Hello"]
       :request [(assoc get-request "content-length" "5") "Hello"]))

  (is (parsed
       [(str "GET / HTTP/1.1\r\n"
             "Content-Length: 10\r\n\r\n")
        "Hello" "World"]
       :request [(assoc get-request "content-length" "10") "HelloWorld"]))

  (is (parsed
       (str "GET / HTTP/1.1\r\n"
            "CONTENT-LENGTH: 11\r\n\r\nHello World")
       :request [(assoc get-request "content-length" "11") "Hello World"]))

  (is (parsed
       (str "POST / HTTP/1.1\r\n"
            "Content-Length: 5\r\n"
            "Expect: 100-continue\r\n\r\n"
            "Hello")
       :request [(assoc post-request
                   "content-length" "5"
                   "expect" "100-continue") :chunked]
       :body "Hello"
       :body nil))

  (is (parsed
       (str "POST / HTTP/1.0\r\n"
            "Content-Length: 5\r\n"
            "Expect: 100-continue\r\n\r\n"
            "Hello")
       :request [(assoc post-request
                   :http-version    [1 0]
                   "content-length" "5"
                   "expect" "100-continue") "Hello"]))

  (is (parsed
       (str "POST /blah HTTP/1.1\r\n"
            "Content-Length: 5\r\n\r\n"
            "Hello")
       :request [(assoc post-request
                   :path-info "/blah"
                   "content-length" "5") "Hello"]))

  (is (parsed
       (apply str
              "POST / HTTP/1.1\r\n"
              "Content-Length: 10000\r\n\r\n"
              (repeat 5000 "x"))
       :request [(assoc post-request "content-length" "10000") :chunked]
       :body    (apply str (repeat 5000 "x"))))

  (is (parsed
       [(apply str
               "POST / HTTP/1.1\r\n"
               "Content-Length: 10000\r\n\r\n"
               (repeat 100 "x"))
        (apply str (repeat 8000 "y"))
        (apply str (repeat 1900 "z"))]
       :request [(assoc post-request "content-length" "10000") :chunked]
       :body    (apply str (repeat 100 "x"))
       :body    (apply str (repeat 8000 "y"))
       :body    (apply str (repeat 1900 "z"))
       :body    nil))

  (is (parsed
       (str "POST / HTTP/1.1\r\n"
            "Content-Length: 5\r\n\r\n"
            "HelloG")
       :request [(assoc post-request "content-length" "5") "Hello"]))

  (is (parsed
       [(str "POST / HTTP/1.1\r\n"       ;; 16
             "Content-Length: 5\r\n\r\n" ;; 37
             "Hell")                     ;; 41
        "o"]
       :request [(assoc post-request "content-length" "5") "Hello"])))

(deftest parsing-chunked-bodies
  (is (parsed
       (str "POST / HTTP/1.1\r\n"
            "Transfer-Encoding: chunked\r\n\r\n"
            "5\r\nHello\r\n"
            "5\r\nWorld\r\n"
            "0\r\n\r\n")
       :request [(assoc post-request "transfer-encoding" "chunked") :chunked]
       :body    "Hello"
       :body    "World"
       :body    nil))

  (is (parsed
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

  (is (parsed
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
       :body    nil))

  (is (thrown?
       HttpParserException
       (parsing
        (str "POST / HTTP/1.1\r\n"
             "Transfer-Encoding: chunked\r\n"
             "\r\n"
             "8000000000000000AA\r\n")))))

(deftest parsing-upgraded-connections
  (is (parsed
       (str "GET / HTTP/1.1\r\n"
            "Connection: Upgrade\r\n\r\n"
            "ZOMGHI2U\r\n")
       :request [(assoc get-request "connection" "upgrade") :upgraded]
       :message "ZOMGHI2U\r\n"))

  (is (parsed
       ["GET / HTTP/1.1\r\n"
        "Connection: UPGRADE\r\n\r\n"
        "GET / HTTP/1.1\r\n\r\n"]
       :request [(assoc get-request "connection" "upgrade") :upgraded]
       :message "GET / HTTP/1.1\r\n\r\n"))

  (is (parsed
       (str "CONNECT / HTTP/1.1\r\n\r\n"
            "Hello world")
       :request [(assoc get-request :request-method "CONNECT") :upgraded]
       :message "Hello world"))

  (is (parsed
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
                   "origin" "http://example.com") :upgraded]
       :message "Hot diggity dogg")))

(deftest keepalive-requests
  (is (parsed
       (str "GET / HTTP/1.1\r\n\r\n"
            "GET / HTTP/1.1\r\n\r\n")
       :request [get-request nil]
       :request [get-request nil]))

  (is (parsed
       ["GET / HTTP/1.1\r\n\r\n"
        "GET / HTTP/1.1\r\n\r\n"]
       :request [get-request nil]
       :request [get-request nil]))

  (is (parsed
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

  (is (parsed
       ["GET / HTTP/1.1\r\n"
        "Host: localhost\r\n\r\n"
        "POST / HTTP/1.1\r\n"
        "Content-Length: 11\r\n\r\n"
        "Hello " "world"
        "POST / HTTP/1.1\r\n"
        "CONTENT-LENGTH: 11\r\n\r\nHello world"
        "GET / HTTP/1.1\r\n\r\n"]
       :request [(assoc get-request "host" "localhost") nil]
       :request [(assoc post-request "content-length" "11") "Hello world"]
       :request [(assoc post-request "content-length" "11") "Hello world"]
       :request [get-request nil]))

  (is (parsed
       [(str "POST / HTTP/1.1\r\n"
             "Transfer-Encoding: chunked\r\n\r\n"
             "5\r\nHello\r\n6\r\n World\r\n0\r\n\r\n")
        (str "POST / HTTP/1.1\r\n"
             "Transfer-Encoding: chunked\r\n\r\n"
             "6\r\nZomg!!\r\n9\r\nINCEPTION\r\n0\r\n\r\n")]
       :request [(assoc post-request "transfer-encoding" "chunked") :chunked]
       :body    "Hello"
       :body    " World"
       :body    nil
       :request [(assoc post-request "transfer-encoding" "chunked") :chunked]
       :body    "Zomg!!"
       :body    "INCEPTION"
       :body    nil))

  (is (parsed
       [(str "GET / HTTP/1.1\r\n\r\n")
        (str "POST /blah HTTP/1.1\r\n"
             "Content-Length: 5\r\n\r\n"
             "Hello")]
       :request [get-request nil]
       :request [(assoc post-request
                   :path-info "/blah"
                   "content-length" "5") "Hello"])))

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
         (repeat "Zomg: HI2U\r\n")))))

  (is (thrown?
       HttpParserException
       (parsing
        (concat
         ["GET / HTTP/1.1\r\n"
          "Zomg: "] (repeat 15 "a")))))

  (is (thrown?
       HttpParserException
       (parsing (concat ["GET /"] (repeat 15 "a")))))

  (is (thrown?
       HttpParserException
       (parsing (concat ["GET / HTTP/1.1\r\n"] (repeat 15 "a"))))))

(deftest http-responses
  (let [q (LinkedBlockingQueue.)]
    (with-parser #(response q %)
      (fn []
        ;; Simple single no content response
        (is (parsed
             (str "HTTP/1.1 204 No Content\r\n\r\n")
             :response [204 {:http-version [1 1]} nil]))

        ;; Multiple sequential no content responses
        (is (parsed
             (str "HTTP/1.1 204 No Content\r\n\r\n"
                  "HTTP/1.1 204 No Content\r\n\r\n")
             :response [204 {:http-version [1 1]} nil]
             :response [204 {:http-version [1 1]} nil]))

        ;; Connection: close response with no body info
        (is (parsed
             (str "HTTP/1.1 200 OK\r\n"
                  "Connection: close\r\n\r\n"
                  "Foo Bar")
             :response [200 {:http-version [1 1] "connection" "close"} nil]
             :body     "Foo Bar"))

        ;; Handling 100 continue
        (is (parsed
             (str "HTTP/1.1 100 Continue\r\n\r\n"
                  "HTTP/1.1 200 OK\r\n"
                  "Content-Length: 5\r\n\r\n"
                  "Hello")
             :response [100 {:http-version [1 1]} nil]
             :response [200 {:http-version [1 1] "content-length" "5"} "Hello"]))

        ;; 204 responses do not have a body
        (is (parsed
             (str "HTTP/1.1 204 No Content\r\n"
                  "Content-Length: 5\r\n\r\n"
                  "HTTP/1.1 204 No Content\r\n"
                  "Transfer-Encoding: chunked\r\n\r\n"
                  "HTTP/1.1 204 No Content\r\n\r\n")
             :response [204 {:http-version [1 1] "content-length" "5"} nil]
             :response [204 {:http-version [1 1] "transfer-encoding" "chunked"} nil]
             :response [204 {:http-version [1 1]} nil]))

        ;; 304 responses do not have a body
        (is (parsed
             (str "HTTP/1.1 304 Not Modified\r\n"
                  "Content-Length: 5\r\n\r\n"
                  "HTTP/1.1 304 Not Modified\r\n"
                  "Transfer-Encoding: chunked\r\n\r\n"
                  "HTTP/1.1 304 Not Modified\r\n\r\n")
             :response [304 {:http-version [1 1] "content-length" "5"} nil]
             :response [304 {:http-version [1 1] "transfer-encoding" "chunked"} nil]
             :response [304 {:http-version [1 1]} nil]))

        ;; Handles responses with no status reasons
        (is (parsed
             (str "HTTP/1.1 200\r\n"
                  "Content-length: 5\r\n\r\n"
                  "Hello")
             :response [200 {:http-version [1 1] "content-length" "5"} "Hello"]))

        ;; Ignores the response body if the request was HEAD
        (.put q (.intern "HEAD"))
        (is (parsed
             (str "HTTP/1.1 200\r\n"
                  "Content-Length: 5\r\n\r\n"
                  "Http/1.1 204\r\n\r\n")
             :response [200 {:http-version [1 1] "content-length" "5"} nil]
             :response [204 {:http-version [1 1]} nil]))

        (.put q (.intern "HEAD"))
        (is (parsed
             (str "HTTP/1.1 200\r\n"
                  "Transfer-Encoding: chunked\r\n\r\n")
             :response [200 {:http-version [1 1] "transfer-encoding" "chunked"} nil]))))))

(use-fixtures :each #(with-parser request %))
