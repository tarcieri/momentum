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
  ;; Simple requests with no body
  (doseq [method ["GET" "POST" "PUT" "DELETE" "HEAD"]]
    (running-call-home-app
     (write method " / HTTP/1.1\r\n\r\n")
     (next-msg-is
      :request [{:server-name    server/SERVER-NAME
                 :script-name    ""
                 :path-info      "/"
                 :request-method method} nil])
     (next-msg-is :done nil)
     (response-is "HTTP/1.1 200 OK\r\n")))

  ;; Simple request with a body
  (running-call-home-app
   (write "POST / HTTP/1.1\r\n"
          "Content-Length: 5\r\n\r\n"
          "Hello\r\n\r\n")
   (next-msg-is-req-with-hdrs {"content-length" "5"})))

(deftest request-callback-happens-before-body-is-recieved
  (running-call-home-app
   (write "POST / HTTP/1.1\r\n"
          "Content-Length: 600000\r\n\r\n")
   (next-msg-is
    :request [{:server-name server/SERVER-NAME
                :script-name ""
                :path-info "/"
                :request-method "POST"
                "content-length" "600000"} nil])
   (no-waiting-messages)))
