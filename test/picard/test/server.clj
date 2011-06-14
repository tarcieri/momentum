(ns picard.test.server
  (:use
   [clojure.test]
   [lamina.core]
   [test-helper]
   [picard.helpers])
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
  :hello-world
  (doseq [method ["GET" "POST" "PUT" "DELETE" "HEAD"]]
    (with-fresh-conn
      (http-write method " / HTTP/1.1\r\n"
                  "Connection: close\r\n\r\n")

      (is (next-msgs
           :request [{:server-name    picard/SERVER-NAME
                      :script-name    ""
                      :path-info      "/"
                      :request-method method
                      :http-version   [1 1]
                      :remote-addr    ["127.0.0.1" :dont-care]
                      :local-addr     ["127.0.0.1" 4040]
                      "connection"    "close"} nil]
           :done nil))

      (is (not-receiving-messages))
      (is (received-response
           "HTTP/1.1 200 OK\r\n"
           "content-type: text/plain\r\n"
           "content-length: 5\r\n"
           "connection: close\r\n\r\n"
           "Hello")))))

(defcoretest simple-http-1-0-request
  :hello-world
  (http-write "GET / HTTP/1.0\r\n\r\n")

  (is (next-msgs
       :request [{:server-name    picard/SERVER-NAME
                  :script-name    ""
                  :path-info      "/"
                  :request-method "GET"
                  :remote-addr    :dont-care
                  :local-addr     :dont-care
                  :http-version   [1 0]} nil]
       :done nil)))

(defcoretest request-and-response-with-duplicated-headers
  (deftrackedapp [dn]
    (fn [evt _]
      (when (= :request evt)
        (dn :response
            [200 {"content-length" "0"
                  "connection"     "close"
                  "foo"            "lol"
                  "bar"            ["omg" "hi2u"]
                  "baz"            ["1" "2" "3"]} ""]))))

  (http-write "GET / HTTP/1.1\r\n"
              ;; stuff
              "baz: lol\r\n"
              "bar: omg\r\n"
              "bar: hi2u\r\n"
              "foo: 1\r\n"
              "foo: 2\r\n"
              "foo: 3\r\n\r\n")

  (is (received-response
       "HTTP/1.1 200 OK\r\n"
       "content-length: 0\r\n"
       "connection: close\r\n"
       "foo: lol\r\n"
       "bar: omg\r\n"
       "bar: hi2u\r\n"
       "baz: 1\r\n"
       "baz: 2\r\n"
       "baz: 3\r\n\r\n"))

  (is (next-msgs
       :request [{:server-name    picard/SERVER-NAME
                  :script-name    ""
                  :path-info      "/"
                  :request-method "GET"
                  :remote-addr    :dont-care
                  :local-addr     :dont-care
                  :http-version   [1 1]
                  "foo"           ["1" "2" "3"]
                  "bar"           ["omg" "hi2u"]
                  "baz"           "lol"} nil]
       :done nil)))

(defcoretest simple-request-with-body
  :hello-world
  (http-write "POST / HTTP/1.1\r\n"
              "Connection: close\r\n"
              "Content-Length: 5\r\n\r\n"
              "Hello")
  (is (next-msgs
       :request [(includes-hdrs {"content-length" "5"}) "Hello"]
       :done    nil))
  (is (not-receiving-messages)))

(defcoretest keepalive-requests
  (deftrackedapp [upstream]
    (fn [evt val]
      (when (= :request evt)
        (upstream :response [200 {"content-length" "5"} "Hello"]))))

  (http-write "GET / HTTP/1.1\r\n\r\n"
              "GET /foo HTTP/1.1\r\n\r\n"
              "POST /bar HTTP/1.1\r\n"
              "connection: close\r\n\r\n")
  (is (next-msgs
       :request [(includes-hdrs {:request-method "GET" :path-info "/"}) nil]
       :done    nil
       :request [(includes-hdrs {:request-method "GET" :path-info "/foo"}) nil]
       :done    nil
       :request [(includes-hdrs {:request-method "POST" :path-info "/bar"
                                 "connection" "close"}) nil]
       :done    nil))

  (is (received-response
       "HTTP/1.1 200 OK\r\n"
       "content-length: 5\r\n\r\n"
       "Hello"
       "HTTP/1.1 200 OK\r\n"
       "content-length: 5\r\n\r\n"
       "Hello"
       "HTTP/1.1 200 OK\r\n"
       "content-length: 5\r\n\r\n"
       "Hello")))

(defcoretest returning-connection-close-terminates-connection
  (fn [downstream]
    (fn [evt val]
      (when (= :request evt)
        (downstream :response [200 {"connection" "close"} "Hello"]))))

  (http-write "GET / HTTP/1.1\r\n\r\n")

  (is (received-response
       "HTTP/1.1 200 OK\r\n"
       "connection: close\r\n\r\n"
       "Hello")))

(defcoretest returning-connection-close-and-chunks
  (fn [downstream]
    (fn [evt val]
      (when (= :request evt)
        (downstream :response [200 {"connection" "close"} :chunked])
        (downstream :body "Hello ")
        (downstream :body "world")
        (downstream :body nil))))

  (http-write "GET / HTTP/1.1\r\n\r\n")

  (is (received-response
       "HTTP/1.1 200 OK\r\n"
       "connection: close\r\n\r\n"
       "Hello world")))

(defcoretest transfer-encoding-chunked-and-keep-alive
  (fn [downstream]
    (defstream
      (request [[hdrs]]
        (if (= "GET" (hdrs :request-method))
          (do
            (downstream :response [200 {"transfer-encoding" "chunked"} :chunked])
            (downstream :body "Hello")
            (downstream :body "World")
            (downstream :body nil))
          (downstream :response [202 {"content-length" "0"}])))))

  (http-write "GET / HTTP/1.1\r\n\r\n")
  (http-write "POST / HTTP/1.1\r\n"
              "Connection: close\r\n\r\n")

  (is (received-response
       "HTTP/1.1 200 OK\r\n"
       "transfer-encoding: chunked\r\n\r\n"
       "5\r\nHello\r\n5\r\nWorld\r\n0\r\n\r\n"
       "HTTP/1.1 202 Accepted\r\n"
       "content-length: 0\r\n\r\n")))

(defcoretest single-chunked-request
  :hello-world
  (http-write "POST / HTTP/1.1\r\n"
              "Connection: close\r\n"
              "Transfer-Encoding: chunked\r\n\r\n"
              "5\r\nHello\r\n0\r\n\r\n")
  (is (next-msgs
       :request [(includes-hdrs {"transfer-encoding" "chunked"}) :chunked]
       :body    "Hello"
       :body    nil
       :done    nil)))

(defcoretest single-chunked-response
  (fn [downstream]
    (fn [evt val]
      (when (= :request evt)
        (downstream :response [200 {"transfer-encoding" "chunked"} :chunked])
        (downstream :body "Hello")
        (downstream :body nil))))

  (http-write "GET / HTTP/1.1\r\n"
              "Connection: close\r\n\r\n")

  (is (received-response
       "HTTP/1.1 200 OK\r\n"
       "transfer-encoding: chunked\r\n\r\n"
       "5\r\nHello\r\n0\r\n\r\n")))

(defcoretest chunked-requests-keep-alive
  (deftrackedapp [upstream]
    (fn [evt val]
      (when (= :request evt)
        (upstream :response [200 {"content-length" "5"} "Hello"]))))

  (http-write "POST / HTTP/1.1\r\n"
              "Transfer-Encoding: chunked\r\n\r\n"
              "5\r\nHello\r\n6\r\n World\r\n0\r\n\r\n")
  (is (next-msgs
       :request [(includes-hdrs {"transfer-encoding" "chunked"}) :chunked]
       :body    "Hello"
       :body    " World"
       :body    nil
       :done    nil))

  (http-write "POST / HTTP/1.1\r\n"
              "Transfer-Encoding: chunked\r\n\r\n"
              "6\r\nZomG!!\r\n9\r\nINCEPTION\r\n0\r\n\r\n")
  (is (next-msgs
       :request [(includes-hdrs {"transfer-encoding" "chunked"}) :chunked]
       :body    "ZomG!!"
       :body    "INCEPTION"
       :body    nil
       :done    nil))

  (http-write "GET / HTTP/1.1\r\n"
              "Connection: close\r\n\r\n")
  (is (next-msgs
       :request [(includes-hdrs {"connection" "close"}) nil]
       :done    nil)))

(defcoretest ignores-unknown-events
  (deftrackedapp [dn]
    (fn [evt val]
      (when (= :request evt)
        (dn :zomg "hi2u")
        (dn :response [200 {"content-length" "0"} nil]))))

  (http-write "GET / HTTP/1.1\r\n"
              "Connection: close\r\n\r\n")

  (is (next-msgs
       :request :dont-care
       :done    nil))

  (is (received-response
       "HTTP/1.1 200 OK\r\n"
       "content-length: 0\r\n\r\n")))

(defcoretest receiving-done-after-http-exchange
  [_ ch]
  (deftrackedapp [dn]
    (fn [evt val]
      (when (= :request evt)
        (dn :response [200 {"content-length" "0"} nil]))
      (when (= :done evt)
        (try
          (dn :done nil)
          (enqueue ch [:error nil])
          (catch Exception err
            (enqueue ch [:error err]))))))

  (http-write "GET / HTTP/1.1\r\n\r\n")

  (is (next-msgs-for ch :error nil)))

(defcoretest aborting-a-request
  :hello-world

  (http-write "POST / HTTP/1.1\r\n"
              "Content-Length: 10000\r\n\r\n"
              "TROLLOLOLOLOLOLLLOLOLOLLOL")

  (close-socket)

  (is (next-msgs
       :request [:dont-care :chunked]
       :abort   #(instance? Exception %)))

  (is (not-receiving-messages)))

(defcoretest applications-raising-errors
  (deftrackedapp [downstream]
    (throw (Exception. "TROLL APP IS TROLLIN'")))

  (http-write "GET / HTTP/1.1\r\n\r\n")

  (is (= 0 (count (netty-exception-events)))))

(defcoretest application-raising-errors-on-request
  (deftrackedapp [downstream]
    (fn [evt val]
      (throw (Exception. "TROLL APP IS TROLLIN'"))))

  (http-write "GET / HTTP/1.1\r\n\r\n")

  (is (= 0 (count (netty-exception-events)))))

(defcoretest upstream-raising-error-during-chunked-request
  (fn [downstream]
    (throw (Exception. "TROLL APP IS TROLLIN'")))

  (http-write "POST / HTTP/1.1\r\n"
              "Transfer-Encoding: chunked\r\n\r\n"
              "5\r\nHello\r\n5\r\nWorld\r\n0\r\n\r\n")

  (is (= 0 (count (netty-exception-events)))))

(defcoretest upstream-raising-error-during-chunked-request-on-request
  (fn [downstream]
    (fn [evt val]
      (throw (Exception. "TROLL APP IS TROLLIN'"))))

  (http-write "POST / HTTP/1.1\r\n"
              "Transfer-Encoding: chunked\r\n\r\n"
              "5\r\nHello\r\n5\r\nWorld\r\n0\r\n\r\n")

  (is (= (count (netty-exception-events)))))

(defcoretest sending-gibberish
  :call-home
  (http-write "lololol wtf is happening?\r\n")
  (is (not-receiving-messages)))

(defcoretest request-callback-happens-before-body-is-recieved
  :hello-world
  (http-write "POST / HTTP/1.1\r\n"
              "Connection: close\r\n"
              "Content-Length: 10000\r\n\r\n")
  (is (next-msgs
       :request [(includes-hdrs
                  {"connection"     "close"
                   "content-length" "10000"}) :chunked]))
  (is (not-receiving-messages))
  (http-write (apply str (for [x (range 10000)] "a"))))

(defcoretest telling-the-application-to-chill-out
  (deftrackedapp [downstream]
    (let [latch (atom true)]
      (fn [evt val]
        (cond
         (= :request evt)
         (do
           (downstream :response [200 {"transfer-encoding" "chunked"} :chunked])
           (bg-while @latch (downstream :body "HAMMER TIME!")))

         (= :pause evt)
         (toggle! latch)

         (= :resume evt)
         (downstream :body nil)))))

  ;; Now the tests
  (http-write "GET / HTTP/1.1\r\n"
              "Connection: close\r\n\r\n")

  (is (next-msgs
       :request :dont-care
       :pause   nil))

  (drain in)

  (is (next-msgs :resume  nil)))

(defcoretest raising-error-during-pause-event
  (deftrackedapp [downstream]
    (let [latch (atom true)]
      (fn [evt val]
        (cond
         (= :request evt)
         (do
           (downstream :response [200 {"transfer-encoding" "chunked"} :chunked])
           (bg-while @latch (downstream :body "HAMMER TIME!")))

         (= :pause evt)
         (do (reset! latch false)
             (throw (Exception. "fail")))))))

  (http-write "GET / HTTP/1.1\r\n"
              "Connection: close\r\n\r\n")

  (drain in)

  (is (next-msgs
       :request :dont-care
       :pause   nil
       :abort   :dont-care))

  (is (not-receiving-messages)))

(defcoretest raising-error-during-resume-event
  (deftrackedapp [downstream]
    (let [latch (atom true)]
      (fn [evt val]
        (cond
         (= :request evt)
         (do
           (downstream :response [200 {"transfer-encoding" "chunked"} :chunked])
           (bg-while @latch (downstream :body "HAMMER TIME!")))

         (= :pause evt)
         (toggle! latch)

         (= :resume evt)
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
  (deftrackedapp [downstream]
    (receive-all
     ch2
     (fn [_]
       (downstream :resume nil)))
    (fn [evt val]
      (cond
       (= :request evt)
       (downstream :pause nil)

       (request-done? evt val)
       (downstream :response [200 {"content-type"   "text/plain"
                                   "content-length" "5"} "Hello"]))))

  ;; Now some tests
  (http-write "POST / HTTP/1.1\r\n"
              "Transfer-Encoding: chunked\r\n"
              "Connection: close\r\n\r\n"
              "5\r\nHello\r\n5\r\nWorld\r\n")

  (is (next-msgs :request :dont-care))
  (is (not-receiving-messages))

  (http-write "5\r\nHello\r\n5\r\nWorld\r\n"
              "3\r\nWTF\r\n2\r\nis\r\n5\r\ngoing\r\n2\r\non\r\n"
              "0\r\n\r\n")

  (is (not-receiving-messages))

  (enqueue ch2 :resume)

  (is (next-msgs
       :body "Hello" :body "World" :body "Hello" :body "World"
       :body "WTF" :body "is" :body "going" :body "on"
       :body nil
       :done nil)))

(defcoretest handling-100-continue-requests-with-100-response
  (deftrackedapp [downstream]
    (fn [evt val]
      (cond
       (= :request evt)
       (downstream :response [100])

       (request-done? evt val)
       (downstream :response [200 {"content-length" "5"} "Hello"]))))

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
       :body nil
       :done nil)))

(defcoretest handling-100-continue-requests-by-responding-directly
  (deftrackedapp [downstream]
    (fn [evt val]
      (when (= :request evt)
        (downstream :response [417 {"content-length" "0"}]))))

  (http-write "POST / HTTP/1.1\r\n"
              "Content-Length: 5\r\n"
              "Connection: close\r\n"
              "Expect: 100-continue\r\n\r\n")

  (is (next-msgs
       :request [(includes-hdrs {"expect" "100-continue"}) :chunked]
       :done    nil))

  (is (received-response
       "HTTP/1.1 417 Expectation Failed\r\n"
       "content-length: 0\r\n\r\n"))

  (is (not-receiving-messages)))

(defcoretest sending-multiple-100-continue-responses
  [ch]
  (fn [downstream]
    (fn [evt val]
      (cond
       (= :request evt)
       (do (downstream :response [100])
           (try (downstream :response [100])
                (catch Exception err
                  (enqueue ch [:error err]))))

       (request-done? evt val)
       (downstream :response [204 {"connection" "close"}]))))

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
  (deftrackedapp [downstream]
    (fn [evt val]
      (when (request-done? evt val)
        (downstream :response [204 {"connection" "close"}]))))

  (http-write "POST / HTTP/1.1\r\n"
              "Content-Length: 5\r\n"
              "Expect: 100-continue\r\n\r\n"
              "Hello")

  (is (next-msgs
       :request [(includes-hdrs {"expect" "100-continue"}) :chunked]
       :body    "Hello"
       :body    nil
       :done    nil))

  (is (received-response
       "HTTP/1.1 204 No Content\r\n"
       "connection: close\r\n\r\n")))

(defcoretest client-sends-body-and-expects-100-2
  (deftrackedapp [downstream]
    (fn [evt val]
      (cond
       (= :request evt)
       (downstream :response [100])

       (request-done? evt val)
       (downstream :response [204 {"connection" "close"}]))))

  (http-write "POST / HTTP/1.1\r\n"
              "Content-Length: 5\r\n"
              "Expect: 100-continue\r\n\r\n"
              "Hello")

  (is (next-msgs
       :request [(includes-hdrs {"expect" "100-continue"}) :chunked]
       :body    "Hello"
       :body    nil
       :done    nil))

  (is (received-response
       "HTTP/1.1 100 Continue\r\n\r\n"
       "HTTP/1.1 204 No Content\r\n"
       "connection: close\r\n\r\n")))

(defcoretest no-request-body-and-expects-100
  (deftrackedapp [downstream]
    (fn [evt val]
      (when (= :request evt)
        (downstream :response [100])
        (downstream :response [204 {"connection" "close"}]))))

  (http-write "POST / HTTP/1.1\r\n"
              "Expect: 100-continue\r\n\r\n")

  (is (next-msgs
       :request [(includes-hdrs {"expect" "100-continue"}) nil]
       :done    nil))

  (is (received-response
       "HTTP/1.1 100 Continue\r\n\r\n"
       "HTTP/1.1 204 No Content\r\n"
       "connection: close\r\n\r\n"))

  (is (not-receiving-messages)))

(defcoretest sending-100-continue-to-1-0-client
  [ch]
  (deftrackedapp [downstream]
    (fn [evt val]
      (when (= :request evt)
        (try
          (downstream :response [100])
          (catch Exception err
            (enqueue ch [:error err])))
        (downstream :response [204 {} nil]))))

  (http-write "POST / HTTP/1.0\r\n"
              "Content-Length: 5\r\n"
              "Expect: 100-continue\r\n\r\n"
              "Hello")

  (is (next-msgs
       :request [(includes-hdrs {:http-version [1 0]
                                 "expect" "100-continue"}) "Hello"]
       :error #(instance? Exception %)
       :done  nil))

  (is (not-receiving-messages)))

(defcoretest sending-100-continue-to-1-0-client-2
  (deftrackedapp [downstream]
    (fn [evt val]
      (when (= :request evt)
        (downstream :response [204 {} nil]))))

  (http-write "POST / HTTP/1.0\r\n"
              "Transfer-encoding: chunked\r\n"
              "Expect: 100-continue\r\n\r\n")

  (is (next-msgs :request [:dont-care :chunked]))

  (http-write "5\r\nHello\r\n0\r\n\r\n")

  (is (next-msgs :body "Hello"
                 :body nil
                 :done nil))

  (is (not-receiving-messages)))

(defcoretest avoiding-abort-loops
  [ch]
  (deftrackedapp [downstream]
    (fn [evt val]
      (downstream :abort nil)))

  (http-write "GET / HTTP/1.1\r\n"
              "Host: localhost\r\n\r\n")

  (is (next-msgs-for
       ch
       :request :dont-care
       :abort   nil))

  (is (not-receiving-messages))
  (is (received-response "")))

(defcoretest closes-exchange-when-receiving-abort
  (deftrackedapp [downstream]
    (fn [evt val]
      (when (= :request evt)
        (send-off
         (agent nil)
         (fn [_]
           (Thread/sleep 10)
           (downstream :abort (Exception. "fail")))))))

  (http-write "GET / HTTP/1.1\r\n"
              "Host: localhost\r\n\r\n")

  (is (received-response "")))

(defcoretest timing-out-without-writing-request
  {:timeout 1}
  (deftrackedapp [downstream] (fn [_ _]))

  ;; Socket is already connected
  (Thread/sleep 2010)

  (is (received-response "")))

(defcoretest timing-out-when-server-doesnt-respond
  {:timeout 1}
  (deftrackedapp [downstream] (fn [evt val]))

  (http-write "GET / HTTP/1.1\r\n"
              "Host: localhost\r\n\r\n")

  (Thread/sleep 2010)

  (is (received-response "")))

(defcoretest timing-out-halfway-streamed-chunked-request
  [ch]
  {:timeout 1}
  (deftrackedapp [downstream] (fn [_ _]))

  (http-write "POST / HTTP/1.1\r\n"
              "Transfer-Encoding: chunked\r\n\r\n"
              "5\r\nHello\r\n5\r\nWorld")

  (is (next-msgs-for
       ch
       :request [:dont-care :chunked]
       :body    "Hello"
       :body    "World"))

  (Thread/sleep 2010)

  (is (next-msgs-for
       ch
       :abort #(instance? Exception %)))

  (is (received-response "")))

(defcoretest each-event-resets-timer
  [ch]
  {:timeout 1}
  (deftrackedapp [dn] (fn [_ _]))

  (http-write "POST / HTTP/1.1\r\n"
              "Transfer-Encoding: chunked\r\n\r\n")

  (is (next-msgs-for ch :request :dont-care))

  (Thread/sleep 800)
  (http-write "5\r\nHello\r\n")
  (is (next-msgs-for ch :body "Hello"))

  (Thread/sleep 800)
  (http-write "5\r\nWorld\r\n")
  (is (next-msgs-for ch :body "World"))

  (Thread/sleep 800)
  (http-write "0\r\n\r\n")
  (is (next-msgs-for ch :body nil)))

(defcoretest timing-out-halfway-through-streamed-chunked-response
  {:timeout 1}
  (deftrackedapp [dn]
    (fn [evt _]
      (when (= :request evt)
        (dn :response [200 {"transfer-encoding" "chunked"} :chunked])
        (dn :body "Hello")
        (dn :body "World"))))

  (http-write "GET / HTTP/1.1\r\n\r\n")

  (is (receiving
       "HTTP/1.1 200 OK\r\n"
       "transfer-encoding: chunked\r\n\r\n"
       "5\r\nHello\r\n5\r\nWorld\r\n"))

  (Thread/sleep 2010)

  (is (received-response "")))

(defcoretest timing-out-during-keepalive
  {:keepalive 1}
  (deftrackedapp [dn]
    (fn [evt _]
      (when (= :request evt)
        (dn :response [200 {"content-length" "5"} "Hello"]))))

  (http-write "GET / HTTP/1.1\r\n\r\n")

  (is (receiving
       "HTTP/1.1 200 OK\r\n"
       "content-length: 5\r\n\r\n"
       "Hello"))

  (Thread/sleep 2010)

  (is (received-response "")))
