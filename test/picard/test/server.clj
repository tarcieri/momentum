(ns picard.test.server
  (:use
   [clojure.test]
   [lamina.core]
   [test-helper])
  (:require
   [picard.server :as server])
  (:import
   [org.jboss.netty.buffer
    ChannelBuffer]
   [java.net
    Socket]
   [java.util.concurrent
    TimeUnit]))

(deftest simple-requests
  ;; Simple requests withq no body
  (doseq [method ["GET" "POST" "PUT" "DELETE" "HEAD"]]
    (running-call-home-app
     (http-write method " / HTTP/1.1\r\n\r\n")
     (is (next-msgs
          :binding nil
          :request [{:server-name    server/SERVER-NAME
                     :script-name    ""
                     :path-info      "/"
                     :request-method method} nil]
          :done nil))
     (response-is "HTTP/1.1 200 OK\r\n")))

  ;; Simple request with a body
  (running-call-home-app
   (http-write "POST / HTTP/1.1\r\n"
               "Content-Length: 5\r\n\r\n"
               "Hello\r\n\r\n")
   (is (next-msgs
        :binding nil
        :request [(includes-hdrs {"content-length" "5"}) "Hello"]))))

(deftest request-callback-happens-before-body-is-recieved
  (running-call-home-app
   (http-write "POST / HTTP/1.1\r\n"
               "Content-Length: 600000\r\n\r\n")
   (is (next-msgs
        :binding nil
        :request [{:server-name server/SERVER-NAME
                   :script-name ""
                   :path-info "/"
                   :request-method "POST"
                   "content-length" "600000"} nil]))
   (no-waiting-messages)))

(deftest multiple-keep-alive-requests
  (running-call-home-app
   (http-write "GET / HTTP/1.1\r\n\r\n"
               "GET /foo HTTP/1.1\r\n\r\n")
   (is (next-msgs
        :binding nil
        :request [(includes-hdrs {:path-info "/"}) nil]))))
