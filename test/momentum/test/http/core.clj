(ns momentum.test.http.core
  (:use
   clojure.test
   momentum.http.core))

(deftest http-1-1-is-keepalive-by-default
  (is (keepalive-request? [{:http-version [1 1]}]))
  (is (keepalive-request? [{:http-version [1 1] "connection" "lulz"}]))
  (is (keepalive-request? [{:http-version [1 1] "connection" "keep-alive"}])))

(deftest http-1-1-connection-close
  (is (not (keepalive-request? [{:http-version [1 1] "connection" "close"}])))
  (is (not (keepalive-request? [{:http-version [1 1] "connection" "Close"}])))
  (is (not (keepalive-request? [{:http-version [1 1] "connection" "CLOSE"}]))))

(deftest http-1-0-is-not-keepalive-by-default
  (is (not (keepalive-request? [{:http-version [1 0]}])))
  (is (not (keepalive-request? [{:http-version [1 0] "connection" "lulz"}])))
  (is (not (keepalive-request? [{:http-version [1 0] "connection" "close"}]))))

(deftest http-1-0-keep-alive
  (is (keepalive-request? [{:http-version [1 0] "connection" "keep-alive"}]))
  (is (keepalive-request? [{:http-version [1 0] "connection" "Keep-Alive"}]))
  (is (keepalive-request? [{:http-version [1 0] "connection" "KEEP-ALIVE"}])))

(deftest does-not-expect-100-by-default
  (is (not (expecting-100? [{:http-version [1 0]}])))
  (is (not (expecting-100? [{:http-version [1 1]}]))))

(deftest does-not-expect-100-when-version-not-1-1
  (is (not (expecting-100? [{:http-version [1 0] "expect" "100-continue"}]))))

(deftest does-not-expect-100-when-the-header-is-not-continue
  (is (not (expecting-100? [{:http-version [1 1] "expect" nil}])))
  (is (not (expecting-100? [{:http-version [1 1] "expect" ""}])))
  (is (not (expecting-100? [{:http-version [1 1] "expect" "lulz"}]))))

(deftest http-1-1-expecting-continue
  (is (expecting-100? [{:http-version [1 1] "expect" "100-continue"}]))
  (is (expecting-100? [{:http-version [1 1] "expect" "100-Continue"}]))
  (is (expecting-100? [{:http-version [1 1] "expect" "100-CONTINUE"}])))

(deftest http-1-1-expecting-continue-multiple-values
  (is (expecting-100? [{:http-version [1 1] "expect" ["100-continue" "lulz"]}]))
  (is (expecting-100? [{:http-version [1 1] "expect" ["100-Continue"]}])))
