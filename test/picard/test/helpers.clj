(ns picard.test.helpers
  (:use
   [clojure.test]
   [picard.helpers])
  (:import
   [java.net
    URL]))

(def default-test-hdrs
  {"host" "www.foo.com"
   :script-name ""
   :path-info "/foo.bar"
   :query-string "k=v"
   :local-addr ["127.0.0.1" 1234]})

(defn- mk-test-request
  [& [hdrs]]
  [(merge default-test-hdrs (or hdrs {})) nil])

;; #request-scheme

(deftest returns-http-by-default
  (is (= "http" (request-scheme {}))))

(deftest returns-https-when-server-says-so
  (is (= "https" (request-scheme {:https "on"})))
  (is (= "https" (request-scheme {:https "on" "x-forwarded-proto" "http"})))
  (is (= "https" (request-scheme {:https "on" "x-forwarded-proto" "http,https"})))
  (is (= "http" (request-scheme {:https "yes"}))))

(deftest returns-https-when-forwarded-for-ssl
  (is (= "https" (request-scheme {"x-forwarded-ssl" "on"})))
  (is (= "http"  (request-scheme {"x-forwarded-ssl" "yes"}))))

(deftest returns-the-first-value-of-http-forwarded-proto
  (is (= "https" (request-scheme {"x-forwarded-proto" "https"})))
  (is (= "http"  (request-scheme {"x-forwarded-proto" "http, https"})))
  (is (= "https" (request-scheme {"x-forwarded-proto" "https, http"}))))

;; #request-url

(deftest simple-request-url-port-80
  (let [expected-url (URL. "http" "www.foo.com" 80 "/foo.bar?k=v")
        req (mk-test-request)]
    (is (= (request-url req) expected-url))))

(deftest simple-request-url-port-8080
  (let [expected-url (URL. "http" "www.foo.com" 8080 "/foo.bar?k=v")
        req (mk-test-request {"host" "www.foo.com:8080"})]
    (is (= (request-url req) expected-url))))

(deftest uses-local-addr-when-host-not-present
  (let [expected-url (URL. "http" "127.0.0.1" 1234 "/foo.bar?k=v")
        req (mk-test-request {"host" nil})]
    (is (= (request-url req) expected-url))))

(deftest appends-script-name-when-present
  (let [expected-url (URL. "http" "www.foo.com" 80 "/foo/bar.baz?k=v")
        req (mk-test-request
             {:script-name "/foo"
              :path-info "/bar.baz"})]
    (is (= (request-url req) expected-url))))

(deftest doesnt-include-qmark-when-query-string-empty
  (let [expected-url (URL. "http" "www.foo.com" 80 "/foo.bar")
        req1 (mk-test-request {:query-string nil})
        req2 (mk-test-request {:query-string ""})]
    (is (= (request-url req1) (request-url req2) expected-url))))

