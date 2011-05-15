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

(defcoretest simple-requests
  :call-home
  (doseq [method ["GET" "POST" "PUT" "DELETE" "HEAD"]]
    (with-fresh-conn
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

(defcoretest simple-request-with-body
  ;; Simple request with a body
  :call-home
  (http-write "POST / HTTP/1.1\r\n"
              "Connection: close\r\n"
              "Content-Length: 5\r\n\r\n"
              "Hello")
  (is (next-msgs
       :request [(includes-hdrs {"content-length" "5"}) "Hello"]))
  (is (not-receiving-messages)))

(defcoretest keepalive-requests
  :call-home
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
       "Hello")))

(defcoretest returning-connection-close-terminates-connection
  (fn [resp request]
    (resp :response [200 {"connection" "close"} "Hello"])
    (fn [evt val] true))

  (http-write "GET / HTTP/1.1\r\n\r\n")

  (is (received-response
       "HTTP/1.1 200 OK\r\n"
       "connection: close\r\n\r\n"
       "Hello")))

(defcoretest returning-connection-close-and-chunks
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
       "Hello world")))

(defcoretest single-chunked-request
  :call-home
  (http-write "POST / HTTP/1.1\r\n"
              "Connection: close\r\n"
              "Transfer-Encoding: chunked\r\n\r\n"
              "5\r\nHello\r\n0\r\n\r\n")
  (is (next-msgs
       :request [(includes-hdrs {"transfer-encoding" "chunked"}) :chunked]
       :body    "Hello"
       :done    nil)))

(defcoretest single-chunked-response
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
       "5\r\nHello\r\n0\r\n\r\n")))

(defcoretest chunked-requests-keep-alive
  :call-home
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
       :request [(includes-hdrs {"connection" "close"}) nil])))

(defcoretest aborting-a-request
  [ch]
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
       :abort   nil)))

(defcoretest applications-raising-errors
  [ch]
  (fn [resp request]
    (enqueue ch [:request request])
    (throw (Exception. "TROLL APP IS TROLLIN'")))

  (http-write "GET / HTTP/1.1\r\n\r\n")

  (is (not-receiving-messages))
  (is (= 0 (count (netty-exception-events)))))

(defcoretest upstream-raising-error-during-chunked-request
  [ch]
  (fn [upstream request]
    (throw (Exception. "TROLL APP IS TROLLIN'")))

  (http-write "POST / HTTP/1.1\r\n"
              "Transfer-Encoding: chunked\r\n\r\n"
              "5\r\nHello\r\n5\r\nWorld\r\n0\r\n\r\n")

  (is (= 0 (count (netty-exception-events)))))

(defcoretest sending-gibberish
  :call-home
  (http-write "lololol wtf is happening?\r\n")
  (is (not-receiving-messages)))

(defcoretest request-callback-happens-before-body-is-recieved
  :call-home
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
  (http-write (apply str (for [x (range 10000)] "a"))))

(defcoretest telling-the-application-to-chill-out
  [ch]
  (fn [resp request]
    (enqueue ch [:request request])
    (resp :response [200 {"transfer-encoding" "chunked"} :chunked])
    ;; The latch will let us pause
    (let [latch (atom true)]
      (bg-while @latch (resp :body "HAMMER TIME!"))
      (fn [evt val]
        (enqueue ch [evt val])
        (when (= :pause evt) (toggle! latch))
        (when (= :resume evt)
          (resp :done nil)))))

  ;; Now the tests
  (http-write "GET / HTTP/1.1\r\n"
              "Connection: close\r\n\r\n")
  (drain in)
  (is (next-msgs
       :request :dont-care
       :pause   nil
       :resume  nil)))

(defcoretest raising-error-during-pause-event
  [ch]
  (fn [resp request]
    (enqueue ch [:request request])
    (let [latch (atom true)]
      (resp :response [200 {"transfer-encoding" "chunked"} :chunked])
      (bg-while @latch (resp :body "HAMMER TIME!"))
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

  (is (not-receiving-messages)))

(defcoretest raising-error-during-resume-event
  [ch]
  (fn [resp request]
    (enqueue ch [:request request])
    (let [latch (atom true)]
      (resp :response [200 {"transfer-encoding" "chunked"} :chunked])
      (bg-while @latch (resp :body "HAMMER TIME!"))
      (fn [evt val]
        (enqueue ch [evt val])
        (when (= :pause evt) (toggle! latch))
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

  (is (not-receiving-messages)))

(defcoretest telling-the-server-to-chill-out
  [ch ch2]
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
       :done nil)))

(defcoretest handling-100-continue-requests
  [ch]
  (fn [upstream request]
    (enqueue ch [:request request])
    (upstream :response [100])
    (fn [evt val]
      (enqueue ch [evt val])
      (when (= :done evt)
        (upstream :response [200 {"content-length" "5"} "Hello"]))))

  (http-write "POST / HTTP/1.1\r\n"
              "Content-Length: 5\r\n"
              "Connection: close\r\n"
              "Expect: 100-continue\r\n\r\n")

  (is (next-msgs
       :request [(includes-hdrs {"expect" "100-continue"}) :chunked]))

  (is (receiving
       "HTTP/1.1 100 Continue\r\n"))

  (http-write "Hello")

  (is (next-msgs
       :body "Hello"
       :done nil)))

;; TODO: Missing tests
;; * A 100-continue test that gives the final status directly
;; * Sending multiple 100 responses in a row
;; * Handling various 100 Continue edge cases
