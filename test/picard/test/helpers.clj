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
   :remote-addr ["127.0.0.1" 1234]})

(defn- mk-test-hdrs
  [& [hdrs]]
  (merge default-test-hdrs (or hdrs {})))

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

(deftest picard.url-scheme-trumps-all
  (is (= "https" (request-scheme {:picard.url-scheme "https"
                                  "x-forwarded-proto" "http"})))
  (is (= "http" (request-scheme {:picard.url-scheme "http"
                                 :https "on"
                                 "x-forwarded-proto" "https"}))))

;; #request-url

(deftest simple-request-url-port-80
  (let [expected-url (URL. "http" "www.foo.com" "/foo.bar?k=v")
        hdrs (mk-test-hdrs)]
    (is (= (request-url hdrs) expected-url))))

(deftest simple-request-url-port-8080
  (let [expected-url (URL. "http" "www.foo.com" 8080 "/foo.bar?k=v")
        hdrs (mk-test-hdrs {"host" "www.foo.com:8080"})]
    (is (= (request-url hdrs) expected-url))))

(deftest uses-local-addr-when-host-not-present
  (let [expected-url (URL. "http" "127.0.0.1" 1234 "/foo.bar?k=v")
        hdrs (mk-test-hdrs {"host" nil})]
    (is (= (request-url hdrs) expected-url))))

(deftest appends-script-name-when-present
  (let [expected-url (URL. "http" "www.foo.com" "/foo/bar.baz?k=v")
        hdrs (mk-test-hdrs
             {:script-name "/foo"
              :path-info "/bar.baz"})]
    (is (= (request-url hdrs) expected-url))))

(deftest doesnt-include-qmark-when-query-string-empty
  (let [expected-url (URL. "http" "www.foo.com" "/foo.bar")
        hdrs1 (mk-test-hdrs {:query-string nil})
        hdrs2 (mk-test-hdrs {:query-string ""})]
    (is (= (request-url hdrs1) (request-url hdrs2) expected-url))))


(deftest accept-encoding-helper-works
  (let [hdrs1 {"accept-encoding" "gzip"}
        hdrs2 {"accept-encoding" "gzip,deflate"}
        hdrs3 {"accept-encoding" "deflate,gzip"}
        hdrs4 {"accept-encoding" "   gzip,   deflate "}
        hdrs5 {"accept-encoding" "gzip,deFlaTE"}]
    (is (= #{"gzip"} (accept-encodings hdrs1)))
    (is (= #{"gzip" "deflate"} (accept-encodings hdrs2)))
    (is (= #{"gzip" "deflate"} (accept-encodings hdrs3)))
    (is (= #{"gzip" "deflate"} (accept-encodings hdrs4)))
    (is (= #{"gzip" "deflate"} (accept-encodings hdrs5)))))

(deftest content-legnth-helper-works
  (is (= 1337 (content-length {"content-length" "1337"})))
  (is (= 1337 (content-length {"content-length" "  1337"})))
  (is (= nil (content-length {"content-lengthaef" "1337"})))
  (is (= nil (content-length {"content-length" "not a number lol!"}))))
