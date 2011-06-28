(ns picard.test.client
  (:use
   [clojure.test]
   [lamina.core]
   [test-helper]
   [picard.helpers])
  (:require
   [picard]
   [picard.netty  :as netty]
   [picard.client :as client]
   [picard.server :as server]))

(defn- mk-tracked-pool
  [ch]
  (client/mk-pool
   {:pipeline-fn
    (fn [pipeline]
      (.addAfter
       pipeline "encoder" "track-msgs"
       (netty/upstream-stage
        (fn [_ evt]
          (if-let [err (netty/exception-event evt)]
            (do (enqueue ch err)
                (.printStackTrace err))))))
      pipeline)}))

(defcoretest simple-requests
  [ch1 ch2]
  :hello-world

  (doseq [method ["GET" "POST" "PUT" "DELETE"]]
    (client/request
     ["localhost" 4040]
     [{:path-info "/" :request-method method}]
     (fn [_]
       (fn [evt val] (enqueue ch2 [evt val]))))

    (is (next-msgs-for
         ch1
         :request [{:http-version   [1 1]
                    :server-name    picard/SERVER-NAME
                    :script-name    ""
                    :path-info      "/"
                    :query-string   ""
                    :request-id     #(string? %)
                    :remote-addr    :dont-care
                    :local-addr     :dont-care
                    :request-method method} nil]
         :done nil))

    (is (next-msgs-for
         ch2
         :connected  nil
         :response   [200
                      {:http-version    [1 1]
                       "content-length" "5"
                       "content-type"   "text/plain"
                       "connection"     "close"}
                      "Hello"]
         :done nil))

    (is (no-msgs-for ch2))))

(defcoretest query-string-request
  [ch1 ch2]
  :hello-world

  (client/request
   ["localhost" 4040]
   [{:request-method "GET" :path-info "/" :query-string "zomg"}]
   (fn [_]
     (fn [evt val] (enqueue ch2 [evt val]))))

  (is (next-msgs-for
       ch1
       :request [{:http-version   [1 1]
                  :server-name    picard/SERVER-NAME
                  :script-name    ""
                  :request-method "GET"
                  :request-id     #(string? %)
                  :path-info      "/"
                  :query-string   "zomg"
                  :remote-addr    :dont-care
                  :local-addr     :dont-care} nil]
       :done nil))

  (is (next-msgs-for
       ch2
       :connected nil
       :response [200 {:http-version [1 1]
                       "content-length" "5"
                       "content-type" "text/plain"
                       "connection" "close"} "Hello"]
       :done nil)))

(defcoretest request-and-response-with-duplicated-headers
  [ch1 ch2]
  (deftrackedapp [dn]
    (fn [evt _]
      (when (= :request evt)
        (dn :response
            [200 {"content-length" "0"
                  "connection"     "close"
                  "foo"            "lol"
                  "bar"            ["omg" "hi2u"]
                  "baz"            ["1" "2" "3"]} ""]))))

  (client/request
   ["localhost" 4040]
   [{:path-info      "/"
     :request-method "GET"
     "baz"           "lol"
     "bar"           ["omg" "hi2u"]
     "lol"           ["1" "2" "3"]}]
   (fn [dn]
     (fn [evt val] (enqueue ch2 [evt val]))))

  (is (next-msgs-for
       ch1
       :request [{:server-name    picard/SERVER-NAME
                  :script-name    ""
                  :path-info      "/"
                  :query-string   ""
                  :request-method "GET"
                  :request-id     #(string? %)
                  :remote-addr    :dont-care
                  :local-addr     :dont-care
                  :http-version   [1 1]
                  "baz"           "lol"
                  "bar"           ["omg" "hi2u"]
                  "lol"           ["1" "2" "3"]} nil]
       :done nil))

  (is (next-msgs-for
       ch2
       :connected nil
       :response  [200 {:http-version    [1 1]
                        "content-length" "0"
                        "connection"     "close"
                        "foo"            "lol"
                        "bar"            ["omg" "hi2u"]
                        "baz"            ["1" "2" "3"]} ""]
       :done nil))

  (is (no-msgs-for ch2)))

(defcoretest receiving-a-chunked-body
  [ch1 ch2]
  (deftrackedapp [downstream]
    (fn [evt val]
      (when (= :request evt)
        (downstream :response [200 {"transfer-encoding" "chunked"} :chunked])
        (downstream :body "Hello")
        (downstream :body "World")
        (downstream :body nil))))

  (client/request
   ["localhost" 4040]
   [{:path-info      "/"
     :request-method "GET"
     "connection"    "close"}]
   (fn [_]
     (fn [evt val]
       (enqueue ch2 [evt val]))))

  (is (next-msgs-for
       ch2
       :connected  nil
       :response   [200 {:http-version [1 1]
                         "transfer-encoding" "chunked"} :chunked]
       :body      "Hello"
       :body      "World"
       :body      nil
       :done      nil))

  (is (no-msgs-for ch2)))

(defcoretest sending-a-chunked-body
  [ch1 ch2]
  :hello-world

  (client/request
   ["localhost" 4040]
   [{:path-info          "/"
     :request-method     "GET"
     "transfer-encoding" "chunked"} :chunked]
   (fn [dn]
     (fn [evt val]
       (enqueue ch2 [evt val])
       (when (= :connected evt)
         (dn :body "Foo!")
         (dn :body "Bar!")
         (dn :body nil)))))

  (is (next-msgs-for
       ch1
       :request [(includes-hdrs {"transfer-encoding" "chunked"}) :chunked]
       :body    "Foo!"
       :body    "Bar!"
       :body    nil
       :done    nil))

  (is (next-msgs-for
       ch2
       :connected nil
       :response   [200 {:http-version    [1 1]
                         "content-type"   "text/plain"
                         "content-length" "5"
                         "connection"     "close"} "Hello"]
       :done nil)))

(defcoretest simple-keep-alive-requests
  [_ ch2]
  (deftrackedapp [downstream]
    (fn [evt val]
      (when (= :request evt)
        (downstream :response [200 {"content-length" "5"} "Hello"]))))

  (client/request
   ["localhost" 4040]
   [{:path-info "/" :request-method "GET"}]
   (fn [_]
     (fn [evt val]
       (enqueue ch2 [evt val]))))

  (is (next-msgs-for
       ch2
       :connected  nil
       :response   [200 {:http-version    [1 1]
                         "content-length" "5"} "Hello"]
       :done nil))

  ;; Need to sleep a second since the response might not have been
  ;; returned yet
  (Thread/sleep 10)
  (client/request
   ["localhost" 4040]
   [{:path-info "/" :request-method "GET"}]
   (fn [_]
     (fn [evt val] (enqueue ch2 [evt val]))))

  (is (next-msgs-for
       ch2
       :connected nil
       :response   [200 {:http-version    [1 1]
                         "content-length" "5"} "Hello"]
       :done nil))

  (Thread/sleep 10)
  (client/request
   ["localhost" 4040]
   [{:path-info          "/zomg"
     :request-method     "POST"
     "transfer-encoding" "chunked"
     "connection"        "close"}
    :chunked]
   (fn [dn]
     (fn [evt val]
       (enqueue ch2 [evt val])
       (when (= :connected evt)
         (dn :body "HELLO")
         (dn :body "WORLD")
         (dn :body nil)))))

  (is (next-msgs-for
       ch2
       :connected nil
       :response   [200 {:http-version    [1 1]
                         "content-length" "5"} "Hello"]
       :done nil))

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
        (downstream :body nil))))

  (let [downstream
        (client/request
         ["localhost" 4040]
         [{:path-info      "/"
           :request-method "GET"
           "connection"    "close"}]
         (fn [dn]
           (fn [evt val]
             (enqueue ch2 [evt val])
             (when (= evt :response)
               (dn :pause nil)))))]

    (receive ch3 (fn [_] (downstream :resume nil))))

  (is (next-msgs-for
       ch2
       :connected nil
       :response  :dont-care))

  (Thread/sleep 30)
  (enqueue ch3 true)

  (is (next-msgs-for
       ch2
       :body "Hello"
       :body "World"
       :body nil
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
     (fn [dn]
       (fn [evt val]
         (enqueue ch2 [evt val])

         (when (= :connected evt)
           (bg-while @latch (dn :body "HAMMER TIME!")))

         (when (= :pause evt) (toggle! latch))
         (when (= :resume evt)
           (dn :body nil)))))

    (is (next-msgs-for
         ch2
         :connected nil
         :response  [200 {:http-version [1 1] "content-length" "5"} "Hello"]
         :pause     nil))

    (enqueue ch3 true)

    (is (next-msgs-for
         ch2
         :resume nil))))

(defcoretest ignores-unknown-events
  [ch1 ch2]
  :hello-world

  (client/request
   ["localhost" 4040]
   [{:path-info          "/"
     :request-method     "POST"
     "transfer-encoding" "chunked"} :chunked]
   (fn [dn]
     (fn [evt val]
       (enqueue ch2 [evt val])
       (when (= :connected evt)
         (dn :zomg "hi2u")
         (dn :body "foo")
         (dn :body nil)))))

  (is (next-msgs-for
       ch1
       :request [:dont-care :chunked]
       :body    "foo"
       :body    nil
       :done    nil))

  (is (next-msgs-for
       ch2
       :connected nil
       :response  :dont-care
       :done      nil)))

(defcoretest sending-done-after-exchange
  [_ ch]
  :hello-world

  (client/request
   ["localhost" 4040]
   [{:path-info      "/"
     :request-method "GET"}] ""
     (fn [dn]
       (fn [evt val]
         (when (= :done evt)
           (try
             (dn :done nil)
             (enqueue ch [:error nil])
             (catch Exception err
               (enqueue ch [:error err])))))))

  (is (next-msgs-for ch :error nil)))

(defcoretest issuing-immediate-abort
  [_ ch]
  :hello-world
  (let [pool (client/mk-pool)
        downstream
        (client/request
         ["localhost" 4040]
         [{:path-info      "/"
           :request-method "POST"} nil]
         {:pool pool}
         (fn [_]
           (fn [evt val]
             (when (and (not= :connected evt)
                        (not= :abort evt))
               (enqueue ch [evt val])))))]

    (downstream :abort nil)

    (is (no-msgs-for ch))
    (picard/shutdown-pool pool)))

(defcoretest handling-100-continue-requests-and-responses
  [ch1 ch2]
  (deftrackedapp [downstream]
    (fn [evt val]
      (when (= :request evt)
        (downstream :response [100]))
      (when (request-done? evt val)
        (downstream
         :response
         [200 {"content-length" "5" "connection" "close"} "Hello"]))))

  (let [downstream
        (client/request
         ["localhost" 4040]
         [{:path-info       "/"
           :request-method  "POST"
           "content-length" "5"
           "expect"         "100-continue"} :chunked]
         (fn [_]
           (fn [evt val]
             (enqueue ch2 [evt val]))))]

    (is (next-msgs-for
         ch1
         :request [(includes-hdrs {"expect" "100-continue"}) :chunked]))

    (is (next-msgs-for
         ch2
         :connected nil
         :response  [100 {:http-version [1 1]} ""]))

    (is (no-msgs-for ch1))
    (is (no-msgs-for ch2))

    (downstream :body "Hello")
    (downstream :body nil)

    (is (next-msgs-for
         ch1
         :body "Hello"
         :body nil
         :done nil))

    (is (next-msgs-for
         ch2
         :response  [200 {:http-version    [1 1]
                          "content-length" "5"
                          "connection"     "close"} "Hello"]
         :done nil))))

(defcoretest ^{:network true} defaults-to-port-80
  [_ ch]
  nil
  (client/request
   ["google.com"]
   [{:path-info "/"
     :request-method "GET"}]
   (fn [_]
     (fn [evt val]
       (when (= :abort evt)
         (.printStackTrace val))
       (enqueue ch [evt val]))))

  (is (next-msgs-for
       ch
       :connected nil
       :response  [200 :dont-care :dont-care])))

(defcoretest connecting-to-an-invalid-server
  [_ ch]
  :hello-world

  (client/request
   ["localhost" 4041]
   [{:path-info      "/"
     :request-method "GET"}]
   (fn [_]
     (fn [evt val]
       (enqueue ch [evt val]))))

  (is (next-msgs-for
       ch
       :abort (cmp-with #(instance? Exception %))))

  (is (no-msgs-for ch)))

(defcoretest observes-local-addr-when-connecting
  [_ ch]
  nil

  (client/request
   ["www.google.com" 80]
   [{:path-info      "/"
     :request-method "GET"}]
   {:pool (client/mk-pool {:local-addr ["127.0.0.1" 12345]})}
   (fn [_]
     (fn [evt val]
       (enqueue ch [evt val]))))

  (is (next-msgs-for
       ch
       :abort #{#(instance? Exception %)}))

  (is (no-msgs-for ch)))

(defcoretest observing-max-connections
  [_ ch1 ch2]
  :slow-hello-world

  (let [pool (client/mk-pool {:max-connections 1})]
    (try
      (doseq [ch [ch1 ch2]]
        (client/request
         ["localhost" 4040]
         [{:path-info      "/"
           :request-method "GET"}]
         {:pool pool}
         (fn [_]
           (fn [evt val]
             (enqueue ch [evt val])))))

      (is (next-msgs-for
           ch1
           :connected nil
           :response  :dont-care
           :done      nil))

      (is (next-msgs-for
           ch2
           :abort #(instance? Exception %)))

      (finally
       (picard/shutdown-pool pool)))))

(defcoretest doesnt-double-increment-connection-counting
  [_ ch1 ch2 ch3]
  :slow-hello-world

  (let [pool (client/mk-pool {:max-connections 2})]
    (doseq [ch [ch1 ch2 ch3]]
      (client/request
       ["localhost" 4040]
       [{:path-info "/"
         :request-method "GET"}]
       {:pool pool}
       (fn [_]
         (fn [evt val] (enqueue ch [evt val])))))

    (is (next-msgs-for
         ch1
         :connected nil
         :response  :dont-care
         :done      nil))

    (is (next-msgs-for
         ch2
         :connected nil
         :response  :dont-care
         :done      nil))

    (is (next-msgs-for ch3 :abort #(instance? Exception %)))

    (picard/shutdown-pool pool)))

(defcoretest ^{:network true} observing-max-per-address-connections
  [_ ch1 ch2 ch3]
  :slow-hello-world

  (let [pool (client/mk-pool {:max-connections-per-address 1})]
    (doseq [ch [ch1 ch2]]
      (client/request
       ["localhost" 4040]
       [{:path-info      "/"
         :request-method "GET"}]
       {:pool pool}
       (fn [_]
         (fn [evt val]
           (enqueue ch [evt val])))))

    (client/request
     ["www.google.com" 80]
     [{:path-info "/"
       :request-method "GET"
       "host" "www.google.com"
       "connection" "close"}]
     {:pool pool}
     (fn [_]
       (fn [evt val]
         (enqueue ch3 [evt val]))))

    (is (next-msgs-for
         ch1
         :connected nil
         :response  :dont-care
         :done      nil))

    (is (next-msgs-for
         ch2
         :abort #(instance? Exception %)))

    (is (next-msgs-for
         ch3
         :connected nil
         :response  :dont-care))

    (picard/shutdown-pool pool)))

(defcoretest handling-abort-loops
  [_ ch2]
  :hello-world

  (client/request
   ["localhost" 4040]
   [{:path-info "/"
     :request-method "GET"}]
   (fn [dn]
     (fn [evt val]
       (enqueue ch2 [evt val])
       (dn :abort nil))))

  (is (next-msgs-for
       ch2
       :connected nil
       :abort     :dont-care))

  (is (no-msgs-for ch2)))

(defcoretest client-acknowledges-disconnect
  nil
  (let [event-channel (channel)
        pipeline-fn (fn [pipeline]
                      (.addAfter
                       pipeline "encoder" "hax"
                       (netty/upstream-stage
                        (fn [ch evt]
                          (when-let [msg (netty/message-event evt)]
                            (.close ch)))))
                      pipeline)
        dummy-app (fn [downstream] (fn [_ _]))
        s (server/start dummy-app {:pipeline-fn pipeline-fn})]
    (try
      (client/request
       ["127.0.0.1" 4040]
       [{:path-info "/"
         :request-method "GET"
         "connection" "close"}]
       (fn [_]
         (fn [evt val]
           (enqueue event-channel [evt val]))))

      (is (next-msgs-for
           event-channel
           :connected nil
           :abort     #(instance? Exception %)))
      (finally
       (server/stop s)))))

(defcoretest sending-multiple-aborts-downstream
  [_ ch]
  (fn [downstream]
    (defstream
      (request [_]
        (downstream :response [200 {"transfer-encoding" "chunked"} :chunked])
        (downstream :body "Hello")
        (downstream :body nil))))

  (client/request
   ["localhost" 4040]
   [{:path-info      "/"
     :request-method "GET"
     "connection"    "close"}]
   {:pool (mk-tracked-pool ch)}
   (fn [dn]
     (fn [evt val]
       (when (= :response evt)
         (dn :abort nil)
         (dn :abort nil)))))

  (is (no-msgs-for ch)))

(defcoretest client-times-out-when-server-never-responses
  [_ ch]
  (fn [dn] (fn [_ _]))

  (client/request
   ["localhost" 4040]
   [{:path-info      "/"
     :request-method "GET"
     "connection"    "close"}]
   {:timeout 1}
   (fn [_]
     (fn [evt val]
       (enqueue ch [evt val]))))

  (Thread/sleep 1050)

  (is (next-msgs-for
       ch
       :connected nil
       :abort     #(instance? Exception %))))

(defcoretest timing-out-halfway-through-streamed-response
  [_ ch]
  (deftrackedapp [dn]
    (fn [evt val]
      (when (= :request evt)
        (dn :response [200 {"transfer-encoding" "chunked"
                            "connection"        "close"} :chunked])
        (dn :body "Hello")
        (dn :body "World"))))

  (client/request
   ["localhost" 4040]
   [{:path-info      "/"
     :request-method "GET"
     "connection"    "close"}]
   {:timeout 1}
   (fn [_]
     (fn [evt val]
       (enqueue ch [evt val]))))

  (is (next-msgs-for
       ch
       :connected nil
       :response  [200 :dont-care :chunked]
       :body      "Hello"
       :body      "World"))

  (Thread/sleep 1)

  (is (next-msgs-for ch :abort #(instance? Exception %))))

(defcoretest timing-out-during-keepalive
  [_ ch]
  (fn [dn]
    (fn [evt val]
      (when (= :request evt)
        (dn :response [200 {"content-length" "5"} "Hello"]))))

  (let [pool (client/mk-pool {:keepalive 1})]
    (client/request
     ["localhost" 4040]
     [{:path-info      "/"
       :request-method "GET"}]
     {:pool pool}
     (fn [_]
       (fn [evt val]
         (enqueue ch [evt val]))))

    (is (next-msgs-for
         ch
         :connected nil
         :response  :dont-care
         :done      nil))

    (Thread/sleep 2050)

    (client/request
     ["localhost" 4040]
     [{:path-info      "/"
       :request-method "GET"}]
     {:pool pool}
     (fn [_]
       (fn [evt val]
         (enqueue ch [evt val]))))

    (is (next-msgs-for
         ch
         :connected nil
         :response  :dont-care
         :done      nil)))

  (is (= 3 (count (netty-connect-evts)))))

(defcoretest doesnt-decrecement-pool-count-for-failed-connections
  [_ ch1 ch2 ch3]
  :slow-hello-world

  (let [pool (client/mk-pool {:max-connections-per-address 1})]
    ;; bogus request
    (client/request
     ["localhost" 666]
     [{:path-info "/"
       :request-method "GET"}]
     {:pool pool}
     (fn [_]
       (fn [evt val]
         (enqueue ch1 [evt val]))))


    (doseq [ch [ch2 ch3]]
      (client/request
       ["localhost" 4040]
       [{:path-info      "/"
         :request-method "GET"}]
       {:pool pool}
       (fn [_]
         (fn [evt val]
           (enqueue ch [evt val])))))

    (is (next-msgs-for
         ch1
         :abort #(instance? Exception %)))

    (is (next-msgs-for
         ch2
         :connected nil
         :response  :dont-care
         :done      nil))

    (is (next-msgs-for
         ch3
         :abort #(instance? Exception %)))

    (picard/shutdown-pool pool)))
