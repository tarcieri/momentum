(ns picard.test.middleware.cookie
  (:use
   [clojure.test]
   [lamina.core]
   [test-helper]
   [picard.helpers]
   [picard.test])
  (:require
   [picard]
   [picard.middleware.cookie :as cookie]
   [picard.server :as server])
  (:import
   [org.jboss.netty.handler.codec.http
    DefaultCookie]))

(deftest simple-get-cookies-test
  (let [cookie-header-atom (atom nil)]
    (with-app
      (cookie/cookie
       (fn [downstream]
         (defstream
           (request [[hdrs body :as req]]
             (reset! cookie-header-atom (:cookies hdrs))
             (downstream :response [200 {} nil])))))

      (GET "/foo" {"cookie" "foo=bar; bar=baz"})
      (is (= 200 (last-response-status)))
      (let [expected-cookies {"foo" "bar" "bar" "baz"}
            actual-cookies @cookie-header-atom]
        (is (every? #(= (.getValue %) (get expected-cookies (.getName %))) actual-cookies))))))


(deftest simple-set-cookie-test
  (with-app
    (cookie/cookie
     (fn [downstream]
       (defstream
         (request [req]
           (downstream :response [200 {:cookies #{(DefaultCookie. "foo" "bar")}} nil])))))

    (GET "/foo")
    (is (includes? (last-response-headers) {"set-cookie" "foo=bar"}))))

(deftest set-cookie-with-path-test
  (with-app
    (cookie/cookie
     (fn [downstream]
       (defstream
         (request [req]
           (let [cookie (DefaultCookie. "foo" "bar")]
             (.setDomain cookie "/foo/bar/baz")
             (downstream :response [200 {:cookies #{cookie}} nil]))))))

    (GET "/foo")
    (is (includes? (last-response-headers)
                   {"set-cookie" "foo=bar;Domain=/foo/bar/baz"}))))
