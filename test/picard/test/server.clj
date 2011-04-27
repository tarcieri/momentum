(ns picard.test.server
  (:use
   [clojure.test]
   [lamina.core]
   [test-helper])
  (:require
   [picard]
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
     (http-write method " / HTTP/1.1\r\n"
                 "Connection: close\r\n"
                 "\r\n")
     (is (next-msgs
          :binding nil
          :request [{:server-name    picard/SERVER-NAME
                     :script-name    ""
                     :path-info      "/"
                     :request-method method
                     "connection"    "close"} nil]
          :done nil))
     (is (received-response
          "HTTP/1.1 200 OK\r\n"
          "content-type: text/plain\r\n"
          "content-length: 5\r\n\r\n"
          "Hello")))))

(deftest simple-request-with-body
  ;; Simple request with a body
  (running-call-home-app
   (http-write "POST / HTTP/1.1\r\n"
               "Connection: close\r\n"
               "Content-Length: 5\r\n\r\n"
               "Hello")
   (is (next-msgs
        :binding nil
        :request [(includes-hdrs {"content-length" "5"}) "Hello"]))))

(deftest keepalive-requests
  (running-call-home-app
   (http-write "GET / HTTP/1.1\r\n\r\n"
               "GET /foo HTTP/1.1\r\n\r\n"
               "POST /bar HTTP/1.1\r\n"
               "connection: close\r\n\r\n")
   (is (next-msgs
        :binding nil
        :request [(includes-hdrs {:request-method "GET" :path-info "/"}) nil]
        :done    nil
        :binding nil
        :request [(includes-hdrs {:request-method "GET" :path-info "/foo"}) nil]
        :done    nil
        :binding nil
        :request [(includes-hdrs {:request-method "POST" :path-info "/bar"
                                  "connection" "close"}) nil]))
   (is (received-response
        "HTTP/1.1 200 OK\r\n"
        "content-type: text/plain\r\n"
        "content-length: 5\r\n\r\n"
        "Hello"
        "HTTP/1.1 200 OK\r\n"
        "content-type: text/plain\r\n"
        "content-length: 5\r\n\r\n"
        "Hello"
        "HTTP/1.1 200 OK\r\n"
        "content-type: text/plain\r\n"
        "content-length: 5\r\n\r\n"
        "Hello"))))

(deftest single-chunked-request
  (running-call-home-app
   (http-write "POST / HTTP/1.1\r\n"
               "Connection: close\r\n"
               "Transfer-Encoding: chunked\r\n\r\n"
               "5\r\nHello\r\n0\r\n\r\n")
   (is (next-msgs
        :binding nil
        :request [(includes-hdrs {"transfer-encoding" "chunked"}) nil]
        :body    "Hello"
        :done    nil))))

(deftest chunked-requests-keep-alive
  (running-call-home-app
   (http-write "POST / HTTP/1.1\r\n"
               "Transfer-Encoding: chunked\r\n\r\n"
               "5\r\nHello\r\n6\r\n World\r\n0\r\n\r\n")
   (is (next-msgs
        :binding nil
        :request [(includes-hdrs {"transfer-encoding" "chunked"}) nil]
        :body    "Hello"
        :body    " World"
        :done    nil))
   (http-write "POST / HTTP/1.1\r\n"
               "Transfer-Encoding: chunked\r\n\r\n"
               "6\r\nZomG!!\r\n9\r\nINCEPTION\r\n0\r\n\r\n")
   (is (next-msgs
        :binding nil
        :request [(includes-hdrs {"transfer-encoding" "chunked"}) nil]
        :body    "ZomG!!"
        :body    "INCEPTION"
        :done    nil))
   (http-write "GET / HTTP/1.1\r\n"
               "Connection: close\r\n\r\n")
   (is (next-msgs
        :binding nil
        :request [(includes-hdrs {"connection" "close"}) nil]
        :done    nil))))

(deftest request-callback-happens-before-body-is-recieved
  (running-call-home-app
   (http-write "POST / HTTP/1.1\r\n"
               "Connection: close\r\n"
               "Content-Length: 10000\r\n\r\n")
   (is (next-msgs
        :binding nil
        :request [{:server-name     picard/SERVER-NAME
                   :script-name     ""
                   :path-info       "/"
                   :request-method  "POST"
                   "connection"     "close"
                   "content-length" "10000"} nil]))
   (is (not-receiving-messages))
   (http-write (apply str (for [x (range 10000)] "a")))))

(deftest multiple-keep-alive-requests
  (running-call-home-app
   (http-write "GET / HTTP/1.1\r\n\r\n"
               "GET /foo HTTP/1.1\r\n"
               "Connection: close\r\n\r\n")
   (is (next-msgs
        :binding nil
        :request [(includes-hdrs {:path-info "/"}) nil]))))

(deftest telling-the-application-to-chill-out
  (with-channels
    [ch _]
    (running-app
     (fn [resp]
       (enqueue ch [:binding nil])
       ;; The latch will let us pause
       (let [latch (atom true)]
         (fn [evt val]
           (enqueue ch [evt val])
           (when (= :pause evt)
             (swap! latch (fn [_] false)))
           (when (= :resume evt)
             (resp :done nil))
           ;; Start the crazy network hammering once the
           ;; request has been received
           (when (= :done evt)
             (resp :respond [200 {"transfer-encoding" "chunked"}])
             (loop []
               (resp :body "28\r\nLOLOLOLOLOLOLOLOLOLOLOLOLOLOLOLOLOLOLOLO\r\n")
               (if @latch (recur)))))))

     ;; Now the tests
     (http-write "GET / HTTP/1.1\r\n"
                 "Connection: close\r\n\r\n")
     (drain in)
     (is (next-msgs
          :binding nil
          :request :dont-care
          :done    nil
          :pause   nil
          :resume  nil)))))

(deftest ^{:focus true} telling-the-server-to-chill-out
  (with-channels
    [ch ch2]
    (running-app
     (fn [resp]
       (receive-all
        ch2
        (fn [_] (resp :resume nil)))
       (enqueue ch [:binding nil])
       (fn [evt val]
         (enqueue ch [evt val])
         (when (= :request evt)
           (resp :pause nil))
         (when (= :done evt)
           (resp :respond [200 {"content-type" "text/plain"
                                "content-length" "5"} "Hello"])
           (resp :done nil))
         1))

     ;; Now some tests
     (http-write "POST / HTTP/1.1\r\n"
                 "Transfer-Encoding: chunked\r\n"
                 "Connection: close\r\n\r\n"
                 "5\r\nHello\r\n5\r\nWorld\r\n0\r\n\r\n")

     (is (next-msgs
          :binding nil
          :request :dont-care))
     (is (not-receiving-messages))

     (enqueue ch2 :resume)

     (is (next-msgs
          :body "Hello"
          :body "World"
          :done nil)))))
