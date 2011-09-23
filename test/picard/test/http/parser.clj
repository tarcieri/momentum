(ns picard.test.http.parser
  (:use
   clojure.test
   picard.http.parser)
  (:require
   [clojure.string :as str])
  (:import
   [picard.http
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

(def request-line
  {:request-method "GET"
   :path-info      "/"
   :query-string   ""
   :http-version   [1 1]})

(defn- parsing
  ([raw] (parsing raw (fn [_ _])))
  ([raw f]
     (let [p (parser f)]
       (if (coll? raw)
         (doseq [raw raw] (parse p raw))
         (parse p raw)))))

(defn- is-parsed-as
  [msg raw & expected]
  (let [res      (atom [])
        expected (vec (map vec (partition 2 expected)))]
    (parsing raw (fn [evt val] (swap! res #(conj % [evt val]))))
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
       :request [(assoc request-line "zomg" "HI2U")])))

(deftest parsing-content-lengths
  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Content-Length: 922337203685477580\r\n\r\n")
       :request [(assoc request-line "content-length" "922337203685477580")]))

  (is (thrown?
       HttpParserException
       (parsing (str "GET / HTTP/1.1\r\n"
                     "Content-Length: lulz\r\n\r\n"))))

  (is (thrown?
       HttpParserException
       (parsing (str "GET / HTTP/1.1\r\n"
                     "Content-Length: 9223372036854775807\r\n\r\n")))))

(deftest ^{:focus true} parsing-transfer-encodings
  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "Transfer-Encoding: chunked\r\n\r\n")
       :request [(assoc request-line "transfer-encoding" "chunked")]))

  ;; (is (parsed-as
  ;;      (str "GET / HTTP/1.1\r\n"
  ;;           "transfer-encoding: chunked \r\n\r\n")
  ;;      :request [(assoc request-line "transfer-encoding" "chunked")]))

  ;; (is (parsed-as
  ;;      (str "GET / HTTP/1.1\r\n"
  ;;           "Transfer-Encoding: chunked;lulz\r\n\r\n")
  ;;      :request [(assoc request-line "transfer-encoding" "chunked;lulz")]))

  ;; (is (parsed-as
  ;;      (str "GET / HTTP/1.1\r\n"
  ;;           "Transfer-encoding: Chunked\r\n\r\n")
  ;;      :request [(assoc request-line "transfer-encoding" "chunked")]))

  ;; (is (parsed-as
  ;;      (str "GET / HTTP/1.1\r\n"
  ;;           "Transfer-encoding: chun\r\n\r\n")
  ;;      :request [(assoc request-line "transfer-encoding" "chun")]))

  (is (parsed-as
       (str "GET / HTTP/1.1\r\n"
            "TRANSFER-ENCODING: zomg\r\n\r\n")
       :request [(assoc request-line "transfer-encoding" "zomg")]))

  ;; (is (parsed-as
  ;;      (str "GET / HTTP/1.1\r\n"
  ;;           "Transfer-Encoding: zomg \r\n\r\n")
  ;;      :request [(assoc request-line "transfer-encoding" "zomg")]))

  ;; (is (parsed-as
  ;;      (str "GET / HTTP/1.1\r\n"
  ;;           "transfer-encoding: zomg;omg\r\n\r\n")
  ;;      :request [(assoc request-line "transfer-encoding" "zomg;omg")]))

  ;; (is (parsed-as
  ;;      (str "GET / HTTP/1.1\r\n"
  ;;           "transfer-encoding: Zomg;OMG\r\n\r\n")
  ;;      :request [(assoc request-line "transfer-encoding" "zomg;omg")]))

  )
