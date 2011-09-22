(ns picard.test.http.parser
  (:use
   clojure.test
   picard.http.parser)
  (:require
   [clojure.string :as str]))

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

(defn- is-parsed-as
  [msg raw & expected]
  (let [res      (atom [])
        expected (vec (map vec (partition 2 expected)))
        p        (parser (fn [evt val] (swap! res #(conj % [evt val]))))]
    (if (coll? raw)
      (doseq [raw raw] (parse p raw))
      (parse p raw))
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
