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
   {:path-info "/test.cgi" :query-string "foo=bar?baz"}})


(def request-line
  {:request-method "GET"
   :path-info      "/"
   :query-string   ""
   :http-version   [1 1]})

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
       :request [{:request-method "GET"
                  :path-info      "/"
                  :query-string   ""
                  :http-version   [1 0]} nil]))

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
       :request [(assoc request-line "zomg" "HI2U") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Zomg:HI2U\r\n\r\n")
       :request [(assoc request-line "zomg" "HI2U") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Zomg  :  HI2U \r\n\r\n")
       :request [(assoc request-line "zomg" "HI2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "Zomg" ": HI2U\r\n\r\n"]
       :request [(assoc request-line "zomg" "HI2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "ZO" "MG" "  : " "HI2U\r\n\r\n"]
       :request [(assoc request-line "zomg" "HI2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: " "H" "I" "2" "U" "\r\n\r\n"]
       :request [(assoc request-line "zomg" "HI2U") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "zomg: HI  2U \r\n\r\n")
       :request [(assoc request-line "zomg" "HI  2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: HI  " "2U\r\n\r\n"]
       :request [(assoc request-line "zomg" "HI  2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: HI " " " " 2U\r\n\r\n"]
       :request [(assoc request-line "zomg" "HI   2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: HI2U" "\r\n\r\n"]
       :request [(assoc request-line "zomg" "HI2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: HI" " " " 2U\r\n\r\n"]
       :request [(assoc request-line "zomg" "HI  2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: HI " " 2U" "\r\n\r\n"]
       :request [(assoc request-line "zomg" "HI  2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: HI" " " "\r\n\r\n"]
       :request [(assoc request-line "zomg" "HI") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: HI " " " " \r\n\r\n"]
       :request [(assoc request-line "zomg" "HI") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "zomg: HI\r\n"
            " 2U\r\n\r\n")
       :request [(assoc request-line "zomg" "HI 2U") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "zomg: HI\r\n"
            "\t2U\r\n\r\n")
       :request [(assoc request-line "zomg" "HI 2U") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "zomg:HI\r\n"
            " 2U\r\n\r\n")
       :request [(assoc request-line "zomg" "HI 2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: HI " "\r\n 2U\r\n\r\n"]
       :request [(assoc request-line "zomg" "HI 2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "Zomg: HI  " "\r\n"
        "  2U\r\n\r\n"]
       :request [(assoc request-line "zomg" "HI 2U") nil]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "Zomg: HI   \r\n"
        "   2U\r\n\r\n"]
       :request [(assoc request-line "zomg" "HI 2U") nil])))

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

(deftest parsing-content-lengths
  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Content-Length: 1000\r\n\r\n")
       :request [(assoc request-line "content-length" "1000") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Content-Length: 922337203685477580\r\n\r\n")
       :request [(assoc request-line "content-length" "922337203685477580") nil]))

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
       :request [(assoc request-line "transfer-encoding" "chunked") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "transfer-encoding: chunked \r\n\r\n")
       :request [(assoc request-line "transfer-encoding" "chunked") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Transfer-Encoding: chunked;lulz\r\n\r\n")
       :request [(assoc request-line "transfer-encoding" "chunked;lulz") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Transfer-encoding: Chunked\r\n\r\n")
       :request [(assoc request-line "transfer-encoding" "chunked") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Transfer-encoding: chun\r\n\r\n")
       :request [(assoc request-line "transfer-encoding" "chun") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "TRANSFER-ENCODING: zomg\r\n\r\n")
       :request [(assoc request-line "transfer-encoding" "zomg") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Transfer-Encoding: zomg \r\n\r\n")
       :request [(assoc request-line "transfer-encoding" "zomg") nil]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "transfer-encoding: zomg;omg\r\n\r\n")
       :request [(assoc request-line "transfer-encoding" "zomg;omg") nil])))

(deftest parsing-identity-bodies
  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Content-Length: 5\r\n"
            "\r\n"
            "Hello")
       :request [(assoc request-line "content-length" "5") "Hello"])))
