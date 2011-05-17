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
  (tracking-middleware hello-world-app)

  (http-write "POST / HTTP/1.1\r\n"
              "Content-Length: 10000\r\n\r\n"
              "TROLLOLOLOLOLOLLLOLOLOLLOL")

  (close-socket)

  (is (next-msgs
       :request [:dont-care :chunked]
       :abort   nil)))

(defcoretest applications-raising-errors
  (deftrackedapp [upstream request]
    (throw (Exception. "TROLL APP IS TROLLIN'")))

  (http-write "GET / HTTP/1.1\r\n\r\n")

  (is (not-receiving-messages))
  (is (= 0 (count (netty-exception-events)))))

(defcoretest upstream-raising-error-during-chunked-request
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
  (deftrackedapp [upstream request]
    (upstream :response [200 {"transfer-encoding" "chunked"} :chunked])
    ;; The latch will let us pause
    (let [latch (atom true)]
      (bg-while @latch (upstream :body "HAMMER TIME!"))
      (fn [evt val]
        (when (= :pause evt) (toggle! latch))
        (when (= :resume evt)
          (upstream :done nil)))))

  ;; Now the tests
  (http-write "GET / HTTP/1.1\r\n"
              "Connection: close\r\n\r\n")
  (drain in)
  (is (next-msgs
       :request :dont-care
       :pause   nil
       :resume  nil)))

(defcoretest raising-error-during-pause-event
  (deftrackedapp [upstream request]
    (let [latch (atom true)]
      (upstream :response [200 {"transfer-encoding" "chunked"} :chunked])
      (bg-while @latch (upstream :body "HAMMER TIME!"))
      (fn [evt val]
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
  (deftrackedapp [upstream request]
    (let [latch (atom true)]
      (upstream :response [200 {"transfer-encoding" "chunked"} :chunked])
      (bg-while @latch (upstream :body "HAMMER TIME!"))
      (fn [evt val]
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
  [_ ch2]
  (deftrackedapp [upstream request]
    (receive-all
     ch2
     (fn [_] (upstream :resume nil)))
    (upstream :pause nil)
    (fn [evt val]
      (when (= :done evt)
        (upstream :response [200 {"content-type" "text/plain"
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

(defcoretest handling-100-continue-requests-with-100-response
  (deftrackedapp [upstream request]
    (upstream :response [100])
    (fn [evt val]
      (when (= :done evt)
        (upstream :response [200 {"content-ength" "5"} "Hello"]))))

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

(defcoretest handling-100-continue-requests-by-responding-directly
  (deftrackedapp [upstream request]
    (upstream :response [417 {"content-length" "0"}])
    (fn [_ _]))

  (http-write "POST / HTTP/1.1\r\n"
              "Content-Length: 5\r\n"
              "Connection: close\r\n"
              "Expect: 100-continue\r\n\r\n")

  (is (next-msgs
       :request [(includes-hdrs {"expect" "100-continue"}) :chunked]))

  (is (received-response
       "HTTP/1.1 417 Expectation Failed\r\n"
       "content-length: 0\r\n\r\n"))

  (is (not-receiving-messages)))

(defcoretest sending-multiple-100-continue-responses
  [ch]
  (fn [upstream request]
    (upstream :response [100])
    (try (upstream :response [100])
         (catch Exception err (enqueue ch [:error err])))
    (fn [evt val]
      (when (= :done evt)
        (upstream :response [204 {"connection" "close"}]))))

  (http-write "POST / HTTP/1.1\r\n"
              "Content-Length: 5\r\n"
              "Expect: 100-continue\r\n\r\n")

  (is (next-msgs
       :error #(instance? Exception %)))

  (is (receiving
       "HTTP/1.1 100 Continue\r\n\r\n"))

  (http-write "Hello")

  (is (received-response
       "HTTP/1.1 204 No Content\r\n"
       "connection: close\r\n\r\n")))

(defcoretest client-sends-body-and-expects-100
  (deftrackedapp [upstream request]
    (fn [evt val]
      (when (= :done evt)
        (upstream :response [204 {"connection" "close"}]))))

  (http-write "POST / HTTP/1.1\r\n"
              "Content-Length: 5\r\n"
              "Expect: 100-continue\r\n\r\n"
              "Hello")

  (is (next-msgs
       :request [(includes-hdrs {"expect" "100-continue"}) :chunked]
       :body    "Hello"
       :done    nil))

  (is (received-response
       "HTTP/1.1 204 No Content\r\n"
       "connection: close\r\n\r\n")))

(defcoretest client-sends-body-and-expects-100-2
  (deftrackedapp [upstream request]
    (upstream :response [100])
    (fn [evt val]
      (when (= :done evt)
        (upstream :response [204 {"connection" "close"}]))))

  (http-write "POST / HTTP/1.1\r\n"
              "Content-Length: 5\r\n"
              "Expect: 100-continue\r\n\r\n"
              "Hello")

  (is (next-msgs
       :request [(includes-hdrs {"expect" "100-continue"}) :chunked]
       :body    "Hello"
       :done    nil))

  (is (received-response
       "HTTP/1.1 100 Continue\r\n\r\n"
       "HTTP/1.1 204 No Content\r\n"
       "connection: close\r\n\r\n")))

(defcoretest no-request-body-and-expects-100
  (deftrackedapp [upstream request]
    (upstream :response [100])
    (upstream :response [204 {"connection" "close"}])
    (fn [evt val]))

  (http-write "POST / HTTP/1.1\r\n"
              "Expect: 100-continue\r\n\r\n")

  (is (next-msgs
       :request [(includes-hdrs {"expect" "100-continue"}) nil]))

  (is (received-response
       "HTTP/1.1 100 Continue\r\n\r\n"
       "HTTP/1.1 204 No Content\r\n"
       "connection: close\r\n\r\n"))

  (is (not-receiving-messages)))

;; TODO: Missing tests
;; * What happens if the client sends a 100-continue but no content-length
;;   or transfer-encoding headers
;; * Handling various 100 Continue edge cases
