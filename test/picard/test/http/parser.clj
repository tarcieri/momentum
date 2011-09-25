(ns picard.test.http.parser
  (:use
   clojure.test
   picard.http.parser)
  (:require
   [clojure.string :as str])
  (:import
   [picard.http
    HttpParser
    HttpParserException]))

(def valid-methods
  ["HEAD"
   "GET"
   "POST"
   "PUT"
   "DELETE"
   "CONNECT"
   "OPTIONS"
   "TRACE"
   "COPY"
   "LOCK"
   "MKCOL"
   "MOVE"
   "PROPFIND"
   "PROPPATCH"
   "UNLOCK"
   "REPORT"
   "MKACTIVITY"
   "CHECKOUT"
   "MERGE"
   "MSEARCH"
   "NOTIFY"
   "SUBSCRIBE"
   "UNSUBSCRIBE"
   "PATCH"])

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

(def standard-headers
  [HttpParser/HDR_ACCEPT
   HttpParser/HDR_ACCEPT_CHARSET
   HttpParser/HDR_ACCEPT_ENCODING
   HttpParser/HDR_ACCEPT_LANGUAGE
   HttpParser/HDR_ACCEPT_RANGES
   HttpParser/HDR_AGE
   HttpParser/HDR_ALLOW
   HttpParser/HDR_AUTHORIZATION
   HttpParser/HDR_CACHE_CONTROL
   HttpParser/HDR_CONNECTION
   HttpParser/HDR_CONTENT_ENCODING
   HttpParser/HDR_CONTENT_LANGUAGE
   HttpParser/HDR_CONTENT_LENGTH
   HttpParser/HDR_CONTENT_LOCATION
   HttpParser/HDR_CONTENT_MD5
   HttpParser/HDR_CONTENT_DISPOSITION
   HttpParser/HDR_CONTENT_RANGE
   HttpParser/HDR_CONTENT_TYPE
   HttpParser/HDR_COOKIE
   HttpParser/HDR_DATE
   HttpParser/HDR_DNT
   HttpParser/HDR_ETAG
   HttpParser/HDR_EXPECT
   HttpParser/HDR_EXPIRES
   HttpParser/HDR_FROM
   HttpParser/HDR_HOST
   HttpParser/HDR_IF_MATCH
   HttpParser/HDR_IF_MODIFIED_SINCE
   HttpParser/HDR_IF_NONE_MATCH
   HttpParser/HDR_IF_RANGE
   HttpParser/HDR_IF_UNMODIFIED_SINCE
   HttpParser/HDR_KEEP_ALIVE
   HttpParser/HDR_LAST_MODIFIED
   HttpParser/HDR_LINK
   HttpParser/HDR_LOCATION
   HttpParser/HDR_MAX_FORWARDS
   HttpParser/HDR_P3P
   HttpParser/HDR_PRAGMA
   HttpParser/HDR_PROXY_AUTHENTICATE
   HttpParser/HDR_PROXY_AUTHORIZATION
   HttpParser/HDR_RANGE
   HttpParser/HDR_REFERER
   HttpParser/HDR_REFRESH
   HttpParser/HDR_RETRY_AFTER
   HttpParser/HDR_SERVER
   HttpParser/HDR_SET_COOKIE
   HttpParser/HDR_STRICT_TRANSPORT_SECURITY
   HttpParser/HDR_TE
   HttpParser/HDR_TRAILER
   HttpParser/HDR_TRANSFER_ENCODING
   HttpParser/HDR_UPGRADE
   HttpParser/HDR_USER_AGENT
   HttpParser/HDR_VARY
   HttpParser/HDR_VIA
   HttpParser/HDR_WARNING
   HttpParser/HDR_WWW_AUTHENTICATE
   HttpParser/HDR_X_CONTENT_TYPE_OPTIONS
   HttpParser/HDR_X_DO_NOT_TRACK
   HttpParser/HDR_X_FORWARDED_FOR
   HttpParser/HDR_X_FORWARDED_PROTO
   HttpParser/HDR_X_FRAME_OPTIONS
   HttpParser/HDR_X_POWERED_BY
   HttpParser/HDR_X_REQUESTED_WITH
   HttpParser/HDR_X_XSS_PROTECTION])

(def request-line
  {:request-method "GET"
   :path-info      "/"
   :query-string   ""
   :http-version   [1 1]})

(defn- buf->str
  [b]
  (when b
    (let [len (.remaining b)
          arr (byte-array len)]
      (.get b arr)
      (String. arr))))

(defn- normalizing
  [f]
  (fn [evt val]
    (if (= :body evt)
      (f :body (buf->str val))
      (f evt val))))

(defn- parsing
  ([raw]
     (let [msgs (atom [])]
       (parsing raw
                (fn [evt val]
                  (swap! msgs #(conj % [evt val]))))
       @msgs))

  ([raw f]
     (let [p (parser (normalizing f))]
       (if (coll? raw)
         (doseq [raw raw] (parse p raw))
         (parse p raw)))))

(defn- is-parsed-as
  [msg raw & expected]
  (let [res      (atom [])
        expected (vec (map vec (partition 2 expected)))]

    (parsing
     raw
     (fn [evt val]
       (swap! res #(conj % [evt val]))))

    (do-report
     {:type    (if (= expected @res) :pass :fail)
      :message  msg
      :expected expected
      :actual   @res})))

(defmethod assert-expr 'parsed-as [msg form]
  (let [[_ raw & expected] form]
    `(is-parsed-as ~msg ~raw ~@expected)))

;; ==== REQUEST LINE TESTS

(deftest parsing-normal-request-lines
  (doseq [method valid-methods]
    (is (parsed-as
         (str method " / HTTP/1.1\r\n\r\n")
         :request [{:request-method method
                    :path-info      "/"
                    :query-string   ""
                    :http-version   [1 1]}])))

  (is (parsed-as
       "GET / HTTP/1.0\r\n\r\n"
       :request [{:request-method "GET"
                  :path-info      "/"
                  :query-string   ""
                  :http-version   [1 0]}]))

  (is (parsed-as
       "GET / HTTP/0.9\r\n\r\n"
       :request [{:request-method "GET"
                  :path-info      "/"
                  :query-string   ""
                  :http-version   [0 9]}]))

  (is (parsed-as
       "GET / HTTP/90.23\r\n\r\n"
       :request [{:request-method "GET"
                  :path-info      "/"
                  :query-string   ""
                  :http-version   [90 23]}])))

(deftest parsing-normal-request-lines-in-chunks
  (doseq [method valid-methods]
    (is (parsed-as
         (str/split (str method " / HTTP/1.1\r\n\r\n") #"")
         :request [{:request-method method
                    :path-info      "/"
                    :query-string   ""
                    :http-version   [1 1]}]))))

(deftest parsing-various-valid-request-uris
  (doseq [[uri hdrs] valid-uris]
    (let [raw (str "GET " uri " HTTP/1.1\r\n\r\n")]
      (is (parsed-as
           raw
           :request [(merge hdrs {:request-method "GET" :http-version [1 1]})]))

      (is (parsed-as
           (str/split raw #"")
           :request [(merge hdrs {:request-method "GET" :http-version [1 1]})]))))

  (is (parsed-as
       ["GET /hello" "/world HTTP/1.1\r\n\r\n"]
       :request [{:request-method "GET"
                  :http-version   [1 1]
                  :path-info      "/hello/world"
                  :query-string   ""}]))

  (is (parsed-as
       ["GET /hello/world?zomg" "whatsup HTTP/1.1\r\n\r\n"]
       :request [{:request-method "GET"
                  :http-version   [1 1]
                  :path-info      "/hello/world"
                  :query-string   "zomgwhatsup"}])))

(deftest parsing-single-headers
  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Zomg: HI2U\r\n\r\n")
       :request [(assoc request-line "zomg" "HI2U")]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Zomg:HI2U\r\n\r\n")
       :request [(assoc request-line "zomg" "HI2U")]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Zomg  :  HI2U \r\n\r\n")
       :request [(assoc request-line "zomg" "HI2U")]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "Zomg" ": HI2U\r\n\r\n"]
       :request [(assoc request-line "zomg" "HI2U")]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "ZO" "MG" "  : " "HI2U\r\n\r\n"]
       :request [(assoc request-line "zomg" "HI2U")]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: " "H" "I" "2" "U" "\r\n\r\n"]
       :request [(assoc request-line "zomg" "HI2U")]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "zomg: HI  2U \r\n\r\n")
       :request [(assoc request-line "zomg" "HI  2U")]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: HI  " "2U\r\n\r\n"]
       :request [(assoc request-line "zomg" "HI  2U")]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: HI " " " " 2U\r\n\r\n"]
       :request [(assoc request-line "zomg" "HI   2U")]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: HI2U" "\r\n\r\n"]
       :request [(assoc request-line "zomg" "HI2U")]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: HI" " " " 2U\r\n\r\n"]
       :request [(assoc request-line "zomg" "HI  2U")]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: HI " " 2U" "\r\n\r\n"]
       :request [(assoc request-line "zomg" "HI  2U")]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: HI" " " "\r\n\r\n"]
       :request [(assoc request-line "zomg" "HI")]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: HI " " " " \r\n\r\n"]
       :request [(assoc request-line "zomg" "HI")]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "zomg: HI\r\n"
            " 2U\r\n\r\n")
       :request [(assoc request-line "zomg" "HI 2U")]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "zomg: HI\r\n"
            "\t2U\r\n\r\n")
       :request [(assoc request-line "zomg" "HI 2U")]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "zomg:HI\r\n"
            " 2U\r\n\r\n")
       :request [(assoc request-line "zomg" "HI 2U")]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "zomg: HI " "\r\n 2U\r\n\r\n"]
       :request [(assoc request-line "zomg" "HI 2U")]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "Zomg: HI  " "\r\n"
        "  2U\r\n\r\n"]
       :request [(assoc request-line "zomg" "HI 2U")]))

  (is (parsed-as
       ["GET / HTTP/1.1\r\n"
        "Zomg: HI   \r\n"
        "   2U\r\n\r\n"]
       :request [(assoc request-line "zomg" "HI 2U")])))

(def header-to-test-val
  {"content-length" "1000"})

(deftest ^{:focus true} parsing-standard-headers
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
       :request [(assoc request-line "content-length" "1000")]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Content-Length: 922337203685477580\r\n\r\n")
       :request [(assoc request-line "content-length" "922337203685477580")]))

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
       :request [(assoc request-line "transfer-encoding" "chunked")]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "transfer-encoding: chunked \r\n\r\n")
       :request [(assoc request-line "transfer-encoding" "chunked")]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Transfer-Encoding: chunked;lulz\r\n\r\n")
       :request [(assoc request-line "transfer-encoding" "chunked;lulz")]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Transfer-encoding: Chunked\r\n\r\n")
       :request [(assoc request-line "transfer-encoding" "chunked")]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Transfer-encoding: chun\r\n\r\n")
       :request [(assoc request-line "transfer-encoding" "chun")]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "TRANSFER-ENCODING: zomg\r\n\r\n")
       :request [(assoc request-line "transfer-encoding" "zomg")]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Transfer-Encoding: zomg \r\n\r\n")
       :request [(assoc request-line "transfer-encoding" "zomg")]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "transfer-encoding: zomg;omg\r\n\r\n")
       :request [(assoc request-line "transfer-encoding" "zomg;omg")])))

(deftest parsing-identity-bodies
  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Content-Length: 5\r\n"
            "\r\n"
            "Hello")
       :request [(assoc request-line "content-length" "5")]
       :body    "Hello"
       :body    nil)))
