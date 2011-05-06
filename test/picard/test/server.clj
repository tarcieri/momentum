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
  (println "simple-requests")
  ;; Simple requests withq no body
  (doseq [method ["GET" "POST" "PUT" "DELETE" "HEAD"]]
    (running-call-home-app
     (http-write method " / HTTP/1.1\r\n"
                 "Connection: close\r\n"
                 "\r\n")
     (is (next-msgs
          :request [{:server-name    picard/SERVER-NAME
                     :script-name    ""
                     :path-info      "/"
                     :request-method method
                     "connection"    "close"} nil]))
     (is (not-receiving-messages))
     (is (received-response
          "HTTP/1.1 200 OK\r\n"
          "content-type: text/plain\r\n"
          "content-length: 5\r\n\r\n"
          "Hello")))))

(deftest simple-request-with-body
  (println "simple-request-with-body")
  ;; Simple request with a body
  (running-call-home-app
   (http-write "POST / HTTP/1.1\r\n"
               "Connection: close\r\n"
               "Content-Length: 5\r\n\r\n"
               "Hello")
   (is (next-msgs
        :request [(includes-hdrs {"content-length" "5"}) "Hello"]))
   (is (not-receiving-messages))))

(deftest keepalive-requests
  (println "keepalive-requests")
  (running-call-home-app
   (http-write "GET / HTTP/1.1\r\n\r\n"
               "GET /foo HTTP/1.1\r\n\r\n"
               "POST /bar HTTP/1.1\r\n"
               "connection: close\r\n\r\n")
   (is (next-msgs
        :request [(includes-hdrs {:request-method "GET" :path-info "/"}) nil]
        :request [(includes-hdrs {:request-method "GET" :path-info "/foo"}) nil]
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

(deftest returning-connection-close-terminates-connection
  (println "returning-connection-close-terminates-connection")
  (running-app
   (fn [resp request]
     (resp :response [200 {"connection" "close"} "Hello"])
     (fn [evt val] true))

   (http-write "GET / HTTP/1.1\r\n\r\n")

   (is (received-response
        "HTTP/1.1 200 OK\r\n"
        "connection: close\r\n\r\n"
        "Hello"))))

(deftest returning-connection-close-and-chunks
  (println "returning-connection-close-and-chunks")
  (running-app
   (fn [resp request]
     (resp :response [200 {"connection" "close"} :chunked])
     (resp :body "Hello ")
     (resp :body "world")
     (resp :done nil)
     (fn [evt val] true))

   (http-write "GET / HTTP/1.1\r\n\r\n")

   (is (received-response
        "HTTP/1.1 200 OK\r\n"
        "connection: close\r\n\r\n"
        "Hello world"))))

(deftest single-chunked-request
  (println "single-chunked-request")
  (running-call-home-app
   (http-write "POST / HTTP/1.1\r\n"
               "Connection: close\r\n"
               "Transfer-Encoding: chunked\r\n\r\n"
               "5\r\nHello\r\n0\r\n\r\n")
   (is (next-msgs
        :request [(includes-hdrs {"transfer-encoding" "chunked"}) :chunked]
        :body    "Hello"
        :done    nil))))

(deftest single-chunked-response
  (println "single-chunked-response")
  (running-app
   (fn [resp request]
     (resp :response [200 {"transfer-encoding" "chunked"} :chunked])
     (resp :body "Hello")
     (resp :done nil)
     (fn [evt val] true))

   (http-write "GET / HTTP/1.1\r\n"
               "Connection: close\r\n\r\n")

   (is (received-response
        "HTTP/1.1 200 OK\r\n"
        "transfer-encoding: chunked\r\n\r\n"
        "5\r\nHello\r\n0\r\n\r\n"))))

(deftest chunked-requests-keep-alive
  (println "chunked-requests-keep-alive")
  (running-call-home-app
   (http-write "POST / HTTP/1.1\r\n"
               "Transfer-Encoding: chunked\r\n\r\n"
               "5\r\nHello\r\n6\r\n World\r\n0\r\n\r\n")
   (is (next-msgs
        :request [(includes-hdrs {"transfer-encoding" "chunked"}) :chunked]
        :body    "Hello"
        :body    " World"
        :done    nil))
   (http-write "POST / HTTP/1.1\r\n"
               "Transfer-Encoding: chunked\r\n\r\n"
               "6\r\nZomG!!\r\n9\r\nINCEPTION\r\n0\r\n\r\n")
   (is (next-msgs
        :request [(includes-hdrs {"transfer-encoding" "chunked"}) :chunked]
        :body    "ZomG!!"
        :body    "INCEPTION"
        :done    nil))
   (http-write "GET / HTTP/1.1\r\n"
               "Connection: close\r\n\r\n")
   (is (next-msgs
        :request [(includes-hdrs {"connection" "close"}) nil]))))

(deftest aborting-a-request
  (println "aborting-a-request")
  (with-channels
    [ch _]
    (running-app
     (fn [resp request]
       (enqueue ch [:request request])
       (fn [evt val]
         (enqueue ch [evt val])
         (when (= :done evt)
           (resp [200 {"connection" "close"} "Hello"]))))

     (http-write "POST / HTTP/1.1\r\n"
                 "Content-Length: 10000\r\n\r\n"
                 "TROLLOLOLOLOLOLLLOLOLOLLOL")

     (close-socket)

     (is (next-msgs
          :request [:dont-care :chunked]
          :abort   nil)))))

(deftest applications-raising-errors
  (println "applications-raising-errors")
  (with-channels
    [ch _]
    (running-app
     (fn [resp request]
       (enqueue ch [:request request])
       (throw (Exception. "TROLL APP IS TROLLIN'")))

     (http-write "GET / HTTP/1.1\r\n\r\n")

     (is (not-receiving-messages))
     (is (= 0
            (count (netty-exception-events)))))))

(deftest sending-gibberish
  (println "sending-gibberish")
  (running-call-home-app
   (http-write "lololol wtf is happening?\r\n")

   (is (not-receiving-messages))))

(deftest request-callback-happens-before-body-is-recieved
  (println "request-callback-happens-before-body-is-received")
  (running-call-home-app
   (http-write "POST / HTTP/1.1\r\n"
               "Connection: close\r\n"
               "Content-Length: 10000\r\n\r\n")
   (is (next-msgs
        :request [{:server-name     picard/SERVER-NAME
                   :script-name     ""
                   :path-info       "/"
                   :request-method  "POST"
                   "connection"     "close"
                   "content-length" "10000"} :chunked]))
   (is (not-receiving-messages))
   (http-write (apply str (for [x (range 10000)] "a")))))

(deftest telling-the-application-to-chill-out
  (println "telling-the-application-to-chill-out")
  (with-channels
    [ch _]
    (running-app
     (fn [resp request]
       (enqueue ch [:request request])
       (resp :response [200 {"transfer-encoding" "chunked"} :chunked])
       ;; The latch will let us pause
       (let [latch (atom true)]
         (send-off
          (agent nil)
          (fn [_]
            (loop []
              (resp :body "28\r\nLOLOLOLOLOLOLOLOLOLOLOLOLOLOLOLOLOLOLOLO\r\n")
              (if @latch (recur)))))
         (fn [evt val]
           (enqueue ch [evt val])
           (when (= :pause evt)
             (swap! latch (fn [_] false)))
           (when (= :resume evt)
             (resp :done nil)))))

     ;; Now the tests
     (http-write "GET / HTTP/1.1\r\n"
                 "Connection: close\r\n\r\n")
     (drain in)
     (is (next-msgs
          :request :dont-care
          :pause   nil
          :resume  nil)))))

(deftest raising-error-during-pause-event
  (println "raising-error-during-pause-event")
  (with-channels
    [ch _]
    (running-app
     (fn [resp request]
       (enqueue ch [:request request])
       (let [latch (atom true)]
         (resp :response [200 {"transfer-encoding" "chunked"} :chunked])
         (send-off
          (agent nil)
          (fn [_]
            (loop []
              (resp :body "28\r\nLOLOLOLOLOLOLOLOLOLOLOLOLOLOLOLOLOLOLOLO\r\n")
              (if @latch (recur)))))
         (fn [evt val]
           (enqueue ch [evt val])
           (when (= :pause evt)
             (swap! latch (fn [_] false))
             (throw (Exception. "fail"))))))

     (http-write "GET / HTTP/1.1\r\n"
                 "Connection: close\r\n\r\n")

     (drain in)

     (is (next-msgs
          :request :dont-care
          :pause   nil
          :abort   :dont-care))

     (is (not-receiving-messages)))))

(deftest raising-error-during-resume-event
  (println "raising-error-during-resume-event")
  (with-channels
    [ch _]
    (running-app
     (fn [resp request]
       (enqueue ch [:request request])
       (let [latch (atom true)]
         (resp :response [200 {"transfer-encoding" "chunked"} :chunked])
         (send-off
          (agent nil)
          (fn [_]
            (loop []
              (resp :body "28\r\nLOLOLOLOLOLOLOLOLOLOLOLOLOLOLOLOLOLOLOLO\r\n")
              (if @latch (recur)))))
         (fn [evt val]
           (enqueue ch [evt val])
           (when (= :pause evt)
             (swap! latch (fn [_] false)))
           (when (= :resume evt)
             (throw (Exception. "fail"))))))

     (http-write "GET / HTTP/1.1\r\n"
                 "Connection: close\r\n\r\n")

     (drain in)

     (is (next-msgs
          :request :dont-care
          :pause   nil
          :resume  nil
          :abort   :dont-care))

     (is (not-receiving-messages)))))

(deftest telling-the-server-to-chill-out
  (println "telling-the-server-to-chill-out")
  (with-channels
    [ch ch2]
    (running-app
     (fn [resp request]
       (receive-all
        ch2
        (fn [_] (resp :resume nil)))
       (enqueue ch [:request request])
       (resp :pause nil)
       (fn [evt val]
         (enqueue ch [evt val])
         (when (= :done evt)
           (resp :response [200 {"content-type" "text/plain"
                                "content-length" "5"} "Hello"]))))

     ;; Now some tests
     (http-write "POST / HTTP/1.1\r\n"
                 "Transfer-Encoding: chunked\r\n"
                 "Connection: close\r\n\r\n"
                 "5\r\nHello\r\n5\r\nWorld\r\n0\r\n\r\n")

     (is (next-msgs
          :request :dont-care))
     (is (not-receiving-messages))

     (enqueue ch2 :resume)

     (is (next-msgs
          :body "Hello"
          :body "World"
          :done nil)))))
