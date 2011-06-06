(ns picard.test.client
  (:use
   [clojure.test]
   [lamina.core]
   [test-helper])
  (:require
   [picard.netty  :as netty]
   [picard.client :as client]
   [picard.server :as server]))

(defcoretest simple-requests
  [ch1 ch2]
  :hello-world

  (doseq [method ["GET" "POST" "PUT" "DELETE" "HEAD"]]
    (client/request
     ["localhost" 4040]
     [{:path-info "/" :request-method method}]
     (fn [_ evt val]
       (enqueue ch2 [evt val])))

    (is (next-msgs-for
         ch1
         :request [{:http-version   [1 1]
                    :server-name    picard/SERVER-NAME
                    :script-name    ""
                    :path-info      "/"
                    :request-method method} nil]))
    (is (next-msgs-for
         ch2
         :response   [200
                      {:http-version    [1 1]
                       "content-length" "5"
                       "content-type"   "text/plain"
                       "connection"     "close"}
                      "Hello"]))))

(defcoretest receiving-a-chunked-body
  [ch1 ch2]
  (deftrackedapp [downstream]
    (fn [evt val]
      (when (= :request evt)
        (downstream :response [200 {"transfer-encoding" "chunked"} :chunked])
        (downstream :body "Hello")
        (downstream :body "World")
        (downstream :done nil))))

  (client/request
   ["localhost" 4040]
   [{:path-info      "/"
     :request-method "GET"
     "connection"    "close"}]
   (fn [_ evt val]
     (enqueue ch2 [evt val])))

  (is (next-msgs-for
       ch2
       :response   [200 {:http-version [1 1]
                         "transfer-encoding" "chunked"} :chunked]
       :body      "Hello"
       :body      "World"
       :done      nil)))

(defcoretest sending-a-chunked-body
  [ch1 ch2]
  :hello-world

  (client/request
   ["localhost" 4040]
   [{:path-info          "/"
     :request-method     "GET"
     "transfer-encoding" "chunked"} :chunked]
   (fn [upstream evt val]
     (enqueue ch2 [evt val])
     (when (= :connected evt)
       (upstream :body "Foo!")
       (upstream :body "Bar!")
       (upstream :done nil))))

  (is (next-msgs-for
       ch1
       :request [(includes-hdrs {"transfer-encoding" "chunked"}) :chunked]
       :body    "Foo!"
       :body    "Bar!"
       :done    nil))

  (is (next-msgs-for
       ch2
       :connected nil
       :response   [200 {:http-version    [1 1]
                         "content-type"   "text/plain"
                         "content-length" "5"
                         "connection"     "close"} "Hello"])))

(defcoretest simple-keep-alive-requests
  [_ ch2]
  (deftrackedapp [downstream]
    (fn [evt val]
      (when (= :request evt)
        (downstream :response [200 {"content-length" "5"} "Hello"]))))

  (client/request
   ["localhost" 4040]
   [{:path-info "/" :request-method "GET"}]
   (fn [_ evt val] (enqueue ch2 [evt val])))

  (is (next-msgs-for
       ch2
       :response   [200 {:http-version    [1 1]
                         "content-length" "5"} "Hello"]))

  (client/request
   ["localhost" 4040]
   [{:path-info "/" :request-method "GET"}]
   (fn [_ evt val] (enqueue ch2 [evt val])))

  (is (next-msgs-for
       ch2
       ;; :connected nil
       :response   [200 {:http-version    [1 1]
                         "content-length" "5"} "Hello"]))

  (client/request
   ["localhost" 4040]
   [{:path-info          "/zomg"
     :request-method     "POST"
     "transfer-encoding" "chunked"
     "connection"        "close"}
    :chunked]
   (fn [upstream-fn evt val]
     (enqueue ch2 [evt val])
     (when (= :connected evt)
       (upstream-fn :body "HELLO")
       (upstream-fn :body "WORLD")
       (upstream-fn :done nil))))

  (is (next-msgs-for
       ch2
       :connected nil
       :response   [200 {:http-version    [1 1]
                         "content-length" "5"} "Hello"]))

  ;; 2 is to account for the connection test-helper makes
  (is (= 2 (count (netty-connect-evts)))))

(defcoretest issuing-pause-resume
  [_ ch2 ch3]
  (fn [downstream]
    (fn [evt val]
      (when (= :request evt)
        (downstream :response [200 {"transfer-encoding" "chunked"} :chunked])
        (downstream :body "Hello")
        (downstream :body "World")
        (downstream :done nil))))

  (let [upstream
        (client/request
         ["Localhost" 4040]
         [{:path-info      "/"
           :request-method "GET"
           "connection"    "close"}]
         (fn [upstream evt val]
           (enqueue ch2 [evt val])
           (when (= evt :response)
             (upstream :pause nil))))]
    (receive ch3 (fn [_] (upstream :resume nil))))

  (is (next-msgs-for
       ch2
       :response :dont-care))

  (Thread/sleep 30)
  (enqueue ch3 true)

  (is (next-msgs-for
       ch2
       :body "Hello"
       :body "World"
       :done nil)))

(defcoretest telling-the-application-to-chill-out
  [_ ch2 ch3]
  (fn [downstream]
    (fn [evt val]
      (when (= :request evt)
        (downstream :response [200 {"content-length" "5"} "Hello"])
        (downstream :pause nil)
        (receive-all ch3 (fn [_] (downstream :resume nil))))))

  (let [latch (atom true)]
    (client/request
     ["localhost" 4040]
     [{:path-info          "/"
       :request-method     "POST"
       "transfer-encoding" "chunked"
       "connection"        "close"} :chunked]
     (fn [upstream-fn evt val]
       (enqueue ch2 [evt val])
       (when (= :connected evt)
         (bg-while @latch (upstream-fn :body "HAMMER TIME!")))
       (when (= :pause evt) (toggle! latch))
       (when (= :resume evt)
         (upstream-fn :done nil))))

    (is (next-msgs-for
         ch2
         :connected nil
         :response  [200 {:http-version [1 1] "content-length" "5"} "Hello"]
         :pause     nil))

    (enqueue ch3 true)

    (is (next-msgs-for
         ch2
         :resume nil))))

(defcoretest issuing-immediate-abort
  [_ ch]
  :hello-world
  (let [upstream
        (client/request
         ["localhost" 4040]
         [{:path-info      "/"
           :request-method "POST"} nil]
         (fn [_ evt val]
           (enqueue ch [evt val])))]
    (upstream :abort nil))

  (is (not-receiving-messages))

  ;; Need to issue a real request to get the connection closed
  (client/request
   ["localhost" 4040]
   [{:path-info      "/"
     :request-method "GET"
     "connection"    "close"}]
   (fn [_ _ _] true)))

(defcoretest handling-100-continue-requests-and-responses
  [ch1 ch2]
  (deftrackedapp [downstream]
    (fn [evt val]
      (when (= :request evt)
        (downstream :response [100]))
      (when (= :done evt)
        (downstream
         :response
         [200 {"content-length" "5" "connection" "close"} "Hello"]))))

  (let [upstream
        (client/request
         ["Localhost" 4040]
         [{:path-info       "/"
           :request-method  "POST"
           "content-length" "5"
           "expect"         "100-continue"} :chunked]
         (fn [upstream evt val]
           (enqueue ch2 [evt val])))]

    (is (next-msgs-for
         ch1
         :request [(includes-hdrs {"expect" "100-continue"}) :chunked]))
    (is (next-msgs-for
         ch2
         :connected nil
         :response  [100 {:http-version [1 1]} ""]))

    (is (no-msgs-for ch1))
    (is (no-msgs-for ch2))

    (upstream :body "Hello")
    (upstream :done nil)

    (is (next-msgs-for
         ch1
         :body "Hello"
         :done nil))
    (is (next-msgs-for
         ch2
         :response [200 {:http-version    [1 1]
                         "content-length" "5"
                         "connection"     "close"} "Hello"]))))

(defcoretest defaults-to-port-80
  [_ ch]
  nil
  (client/request
   ["google.com"]
   [{:path-info "/"
     :request-method "GET"}]
   (fn [_ evt val]
     (if (= :response evt)
       (enqueue ch [evt val]))))

  (is (next-msgs-for
       ch
       :response [200 :dont-care :dont-care])))

(defcoretest connecting-to-an-invalid-server
  [_ ch]
  :hello-world
  (client/request
   ["localhost" 4041]
   [{:path-info      "/"
     :request-method "GET"}]
   (fn [_ evt val] (enqueue ch [evt val])))

  (is (next-msgs-for
       ch
       :abort (cmp-with #(instance? Exception %)))))

(defcoretest observes-local-addr-when-connecting
  [_ ch]
  nil
  (client/request
   (client/mk-pool {:local-addr ["127.0.0.1" 12345]})
   ["www.google.com" 80]
   [{:path-info      "/"
     :request-method "GET"}]
   (fn [_ evt val] (enqueue ch [evt val])))

  (is (next-msgs-for
       ch
       :abort #{#(instance? Exception %)})))

(defcoretest observing-max-connections
  [_ ch]
  :slow-hello-world

  (let [pool (client/mk-pool {:max-connections 1})]
    (dotimes [_ 2]
      (client/request
       pool ["localhost" 4040]
       [{:path-info      "/"
         :request-method "GET"}]
       (fn [_ evt val]
         (enqueue ch [evt val]))))

    (is (next-msgs-for
         ch
         :abort    (cmp-with (fn [v] (instance? Exception v)))
         :response :dont-care))

    ;; Close off the pool
    (client/request
     pool ["localhost" 4040]
     [{:path-info      "/"
       :request-method "GET"
       "connection"    "close"}]
     (fn [_ _ _]))))

(defcoretest observing-max-per-address-connections
  [_ ch2]
  :slow-hello-world

  (let [pool (client/mk-pool {:max-connections-per-address 1})]
    (dotimes [_ 2]
      (client/request
       pool ["localhost" 4040]
       [{:path-info      "/"
         :request-method "GET"}]
       (fn [_ evt val]
         (enqueue ch2 [evt val]))))

    (client/request
     pool ["127.0.0.1" 4040]
     [{:path-info "/"
       :request-method "GET"
       "connection" "close"}]
     (fn [_ evt val]
       (enqueue ch2 [evt val])))

    (is (next-msgs-for
         ch2
         :abort    (cmp-with (fn [v] (instance? Exception v)))
         :response :dont-care
         :response :dont-care))

    (client/request
     pool ["localhost" 4040]
     [{:path-info      "/"
       :request-method "GET"
       "connection"    "close"}]
     (fn [_ _ _]))))

(defcoretest handling-abort-loops
  [_ ch2]
  :hello-world

  (client/request
   ["localhost" 4040]
   [{:path-info "/"
     :request-method "GET"}]
   (fn [upstream evt val]
     (enqueue ch2 [evt val])
     (upstream :abort nil)))

  (is (next-msgs-for
       ch2
       :response :dont-care
       :abort    :dont-care))

  (is (no-msgs-for ch2)))
