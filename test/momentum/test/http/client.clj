(ns momentum.test.http.client
  (:use
   clojure.test
   support.helpers
   momentum.core
   momentum.http.client)
  (:require
   [momentum.net.server  :as net]
   [momentum.http.server :as server])
  (:import
   [java.io
    IOException]))

(defn- start-server [& args]
  (doto (apply server/start args)
    deref))

(defn- retain*
  [maybe-buffer]
  (cond
   (vector? maybe-buffer)
   (vec (map retain* maybe-buffer))

   :else
   (retain maybe-buffer)))

(defn- connection-tracker
  [app ch]
  (fn [dn env]
    (enqueue ch [:connect nil])
    (app dn env)))

(defn- tracking-connections
  [ch app]
  (net/start
   (-> app server/proto
       (connection-tracker ch))))

(defn- start-conn-tracking-hello-world
  [ch]
  (tracking-connections
   ch (fn [dn _]
        (fn [evt val]
          (when (= :request evt)
            (dn :response [200 {"content-length" "5"} (buffer "Hello")]))))))

(defn- start-hello-world-app
  ([] (start-hello-world-app nil))
  ([ch]
     (server/start
      (fn [dn _]
        (fn [evt val]
          (when ch (enqueue ch [evt val]))
          (when (= :request evt)
            (dn :response [200 {"content-type"   "text/plain"
                                "content-length" "5"
                                "connection"     "close"} (buffer "Hello")])))))))

(defcoretest simple-requests
  [ch1 ch2]
  (start-hello-world-app ch1)

  (doseq [method ["GET" "POST" "PUT" "DELETE"]]
    (connect
     (fn [dn _]
       (fn [evt val]
         (enqueue ch2 [evt val])
         (when (= :open evt)
           (dn :request [{:path-info "/" :request-method method} (buffer "")]))))
     {:host "localhost" :port 4040})

    (is (next-msgs
         ch1
         :request [{:http-version   [1 1]
                    :script-name    ""
                    :path-info      "/"
                    :query-string   ""
                    :request-method method
                    :remote-addr    :dont-care
                    :local-addr     :dont-care} nil]))

    (is (next-msgs
         ch2
         :open     {:local-addr  ["127.0.0.1" #(number? %)]
                    :remote-addr ["127.0.0.1" #(number? %)]}
         :response [200 {:http-version    [1 1]
                         "content-length" "5"
                         "content-type"   "text/plain"
                         "connection"     "close"} "Hello"]
         :close nil))

    (is (no-msgs ch1 ch2))))

(defcoretest query-string-request
  [ch1 ch2]
  (start-hello-world-app ch1)

  (connect
   (fn [dn _]
     (fn [evt val]
       (enqueue ch2 [evt val])
       (when (= :open evt)
         (dn :request [{:request-method "GET"
                        :path-info      "/"
                        :query-string   "zomg"}]))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :request [{:http-version   [1 1]
                  :script-name    ""
                  :path-info      "/"
                  :query-string   "zomg"
                  :request-method "GET"
                  :remote-addr    :dont-care
                  :local-addr     :dont-care} nil]))

  (is (next-msgs
       ch2
       :open :dont-care
       :response [200 {:http-version [1 1]
                       "content-length" "5"
                       "content-type"   "text/plain"
                       "connection"     "close"} "Hello"]
       :close nil)))

(defcoretest blank-path-info
  [ch1]
  (start-hello-world-app ch1)

  (connect
   (fn [dn _]
     (fn [evt val]
       (when (= :open evt)
         (dn :request [{:request-method "GET"
                        :path-info      ""}]))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :request [#(includes-hdrs {:path-info "/"} %) :dont-care])))

(defcoretest request-and-response-with-duplicated-headers
  [ch1 ch2]
  (server/start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [200 {"content-length" "0"
                             "connection"     "close"
                             "foo"            "lol"
                             "bar"            ["omg" "hi2u"]
                             "baz"            ["1" "2" "3"]} (buffer "")])))))

  (connect
   (fn [dn _]
     (fn [evt val]
       (enqueue ch2 [evt val])
       (when (= :open evt)
         (dn :request [{:request-method "GET"
                        :path-info      "/"
                        "baz"           "lol"
                        "bar"           ["omg" "hi2u"]
                        "lol"           ["1" "2" "3"]}]))))
   {:host "127.0.0.1" :port 4040})

  (is (next-msgs
       ch1
       :request [#(includes-hdrs {"baz" "lol"
                                  "bar" ["omg" "hi2u"]
                                  "lol" ["1" "2" "3"]} %) nil]))

  (is (next-msgs
       ch2
       :open :dont-care
       :response [200 #(includes-hdrs {"foo" "lol"
                                       "bar" ["omg" "hi2u"]
                                       "baz" ["1" "2" "3"]} %) ""]
       :close nil))

  (is (no-msgs ch2)))

(defcoretest header-assoc!-regression
  [ch1]
  (server/start
   (fn [dn _]
     (fn [evt val]
       (when (= :request evt)
         (dn :response [200 {"date"             "Tue, 31 Jan 2012 05:48:17 GMT"
                             "server"           "Apache"
                             "content-location" "index.en.html"
                             "vary"             "negotiate,accept-language,Accept-Encoding"
                             "tcn"              "choice"
                             "last-modified"    "Tue, 31 Jan 2012 03:39:36 GMT"
                             "etag"             "3885-4b7cab6438e00"
                             "accept-ranges"    "bytes"
                             "content-length"   "5"}
                        (buffer "Hello")])))))

  (connect
   (fn [dn _]
     (fn [evt val]
       (cond
        (= :open evt)
        (dn :request [{:request-method "GET"
                       :path-info      "/"}])

        (= :response evt)
        (enqueue ch1 [evt val]))))
   {:host "127.0.0.1" :port 4040})

  (is (next-msgs
       ch1
       :response [200 {:http-version      [1 1]
                       "date"             "Tue, 31 Jan 2012 05:48:17 GMT"
                       "server"           "Apache"
                       "content-location" "index.en.html"
                       "vary"             "negotiate,accept-language,Accept-Encoding"
                       "tcn"              "choice"
                       "last-modified"    "Tue, 31 Jan 2012 03:39:36 GMT"
                       "etag"             "3885-4b7cab6438e00"
                       "accept-ranges"    "bytes"
                       "content-length"   "5"}
                  "Hello"])))

(defcoretest receiving-a-chunked-body
  [ch1]
  (server/start
   (fn [dn _]
     (fn [evt val]
       (when (= :request evt)
         (dn :response [200 {"transfer-encoding" "chunked"} :chunked])
         (dn :body (buffer"Hello"))
         (dn :body (buffer "World"))
         (dn :body nil)))))

  (connect
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt (retain* val)])
       (when (= :open evt)
         (dn :request [{:request-method "GET"
                        :path-info      "/"
                        "connection"    "close"}]))))
   {:host "127.0.0.1" :port 4040})

  (is (next-msgs
       ch1
       :open     :dont-care
       :response [200 #(includes-hdrs {"transfer-encoding" "chunked"} %) :chunked]
       :body     "Hello"
       :body     "World"
       :body     nil
       :close    nil))

  (is (no-msgs ch1)))

(defcoretest receiving-a-chunked-body-connection-close
  [ch1]
  (server/start
   (fn [dn _]
     (fn [evt val]
       (when (= :request evt)
         (dn :response [200 {"connection" "close"} :chunked])
         (future
           (Thread/sleep 20)
           (dn :body (buffer "hello "))
           (Thread/sleep 40)
           (dn :body (buffer "world"))
           (dn :body nil))))))

  (connect
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :open evt)
         (dn :request [{:request-method "GET" :path-info "/"} nil]))))
   {:host "127.0.0.1" :port 4040})

  (is (next-msgs
       ch1
       :open     :dont-care
       :response [200 #(includes-hdrs {"connection" "close"} %) :chunked]
       :body     "hello "
       :body     "world"
       :body     nil
       :close    nil)))

(defcoretest sending-a-chunked-body
  [ch1 ch2]
  (start-hello-world-app ch1)

  (connect
   (fn [dn _]
     (fn [evt val]
       (enqueue ch2 [evt val])
       (when (= :open evt)
         (dn :request [{:request-method "GET"
                        :path-info      "/"
                        "transfer-encoding" "chunked"} :chunked])
         (dn :body (buffer "Foo!"))
         (dn :body (buffer "Bar!"))
         (dn :body nil))))
   {:host "localhost" :port 4040})

  (is (next-msgs
       ch1
       :request [#(includes-hdrs {"transfer-encoding" "chunked"} %) :chunked]
       :body    "Foo!"
       :body    "Bar!"
       :body    nil))

  (is (next-msgs
       ch2
       :open     :dont-care
       :response [200 :dont-care "Hello"]
       :close    nil))

  (is (no-msgs ch1 ch2)))

(defcoretest ^{:focus true} simple-keep-alive-requests
  [ch1 ch2 ch3]
  (start-server
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt (retain* val)])
       (when (= :request evt)
         (dn :response [200 {"content-length" "5"} (buffer "hello")]))))
   {:pipeline false})

  (connect
   (fn [dn _]
     (doseq* [[evt val] (seq ch3)]
             (dn evt val))

     (fn [evt val]
       (enqueue ch2 [evt (retain val)])))
   {:host "localhost" :port 4040})

  (is (next-msgs ch2 :open :dont-care))

  (enqueue ch3 [:request [{:request-method "GET" :path-info "/"} nil]])

  (is (next-msgs ch2 :response [200 {:http-version [1 1] "content-length" "5"} "hello"]))

  (enqueue ch3
           [:request [{:request-method     "POST"
                       :path-info          "/"
                       "transfer-encoding" "chunked"} :chunked]])
  (enqueue ch3 [:body (buffer "Hello")])
  (enqueue ch3 [:body (buffer "World")])
  (enqueue ch3 [:body nil])

  (is (next-msgs ch2 :response :dont-care))

  (enqueue ch3 [:request [{:request-method "GET" :path-info "/"} nil]])

  (is (next-msgs ch2 :response [200 {:http-version [1 1] "content-length" "5"} "hello"]))

  (enqueue ch3 [:close nil])

  (is (next-msgs
       ch1
       :open    :dont-care
       :request :dont-care
       :request :dont-care
       :body    "Hello"
       :body    "World"
       :body    nil
       :request :dont-care
       :close   nil))

  ;; (connect
  ;;  (fn [dn _]
  ;;    (fn [evt val]
  ;;      (enqueue ch2 [evt val])
  ;;      (when (= :open evt)
  ;;        (dn :request [{:request-method "GET" :path-info "/zomg"
  ;;                       "transfer-encoding" "chunked" "connection" "close"} :chunked])
  ;;        (dn :body (buffer "HELLO"))
  ;;        (dn :body (buffer "WORLD"))
  ;;        (dn :body nil))))
  ;;  {:host "localhost" :port 4040})
  )

;; (defcoretest keepalive-head-requests
;;   [ch1 ch2]
;;   (start-conn-tracking-hello-world ch1)

;;   (let [pool (client {:pool {:keepalive 1}})]
;;     (dotimes [_ 3]
;;       (connect
;;        pool
;;        (fn [dn _]
;;          (fn [evt val]
;;            (enqueue ch2 [evt val])
;;            (when (= :open evt)
;;              (dn :request [{:request-method "HEAD" :path-info "/"}]))))
;;        {:host "localhost" :port 4040})

;;       (is (next-msgs
;;            ch2
;;            :open     :dont-care
;;            :response [200 {:http-version [1 1] "content-length" "5"} nil]
;;            :done     nil))

;;       (Thread/sleep 50)))

;;   (is (next-msgs ch1 :connect nil))
;;   (is (no-msgs ch1 ch2)))

;; (defcoretest keepalive-head-requests-te-chunked
;;   [ch1 ch2]
;;   (tracking-connections
;;    ch1 (fn [dn _]
;;          (fn [evt val]
;;            (when (= :request evt)
;;              (dn :response [200 {"transfer-encoding" "chunked"
;;                                  "foo" "bar"} nil])))))

;;   (let [pool (client {:pool {:keepalive 1}})]
;;     (dotimes [_ 3]
;;       (connect
;;        pool
;;        (fn [dn _]
;;          (fn [evt val]
;;            (enqueue ch2 [evt val])
;;            (when (= :open evt)
;;              (dn :request [{:request-method "HEAD" :path-info "/"}]))))
;;        {:host "localhost" :port 4040})

;;       (is (next-msgs
;;            ch2
;;            :open     :dont-care
;;            :response [200 {:http-version [1 1] "foo" "bar" "transfer-encoding" "chunked"} nil]
;;            :done     nil))

;;       (Thread/sleep 50)))

;;   (is (next-msgs ch1 :connect nil))
;;   (is (no-msgs ch1 ch2)))

;; (defcoretest keepalive-204-responses
;;   [ch1 ch2]
;;   (tracking-connections
;;    ch1 (fn [dn _]
;;          (fn [evt val]
;;            (when (= :request evt)
;;              (dn :response [204 {} nil])))))

;;   (let [pool (client {:pool {:keepalive 1}})]
;;     (dotimes [_ 3]
;;       (connect
;;        pool
;;        (fn [dn _]
;;          (fn [evt val]
;;            (enqueue ch2 [evt val])
;;            (when (= :open evt)
;;              (dn :request [{:request-method "GET" :path-info "/"}]))))
;;        {:host "localhost" :port 4040})

;;       (is (next-msgs
;;            ch2
;;            :open     :dont-care
;;            :response [204 {:http-version [1 1]} nil]
;;            :done     nil))

;;       (Thread/sleep 50)))

;;   (is (next-msgs ch1 :connect nil))
;;   (is (no-msgs ch1 ch2)))

;; (defcoretest keepalive-304-responses
;;   [ch1 ch2]
;;   (tracking-connections
;;    ch1 (fn [dn _]
;;          (fn [evt val]
;;            (when (= :request evt)
;;              (dn :response [304 {"content-length" "10000"} nil])))))

;;   (let [pool (client {:pool {:keepalive 1}})]
;;     (dotimes [_ 3]
;;       (connect
;;        pool
;;        (fn [dn _]
;;          (fn [evt val]
;;            (enqueue ch2 [evt val])
;;            (when (= :open evt)
;;              (dn :request [{:request-method "GET" :path-info "/"}]))))
;;        {:host "localhost" :port 4040})

;;       (is (next-msgs
;;            ch2
;;            :open     :dont-care
;;            :response [304 {:http-version [1 1] "content-length" "10000"} nil]
;;            :done     nil))

;;       (Thread/sleep 50)))

;;   (is (next-msgs ch1 :connect nil))
;;   (is (no-msgs ch1 ch2)))

;; (defcoretest keepalive-304-responses-chunked
;;   [ch1 ch2]
;;   (tracking-connections
;;    ch1 (fn [dn _]
;;          (fn [evt val]
;;            (when (= :request evt)
;;              (dn :response [304 {"transfer-encoding" "chunked"} nil])))))

;;   (let [pool (client {:pool {:keepalive 1}})]
;;     (dotimes [_ 3]
;;       (connect
;;        pool
;;        (fn [dn _]
;;          (fn [evt val]
;;            (enqueue ch2 [evt val])
;;            (when (= :open evt)
;;              (dn :request [{:request-method "GET" :path-info "/"}]))))
;;        {:host "localhost" :port 4040})

;;       (is (next-msgs
;;            ch2
;;            :open     :dont-care
;;            :response [304 {:http-version [1 1] "transfer-encoding" "chunked"} nil]
;;            :done     nil))

;;       (Thread/sleep 50)))

;;   (is (next-msgs ch1 :connect nil))
;;   (is (no-msgs ch1 ch2)))

(defn- request-done?
  [evt val]
  (or (= [:body nil] [evt val])
      (and (= :request evt)
           (let [[_ body] val]
             (not (keyword? body))))))

(defcoretest handling-100-continue-requests-and-responses
  [ch1 ch2 ch3]
  (server/start
   (fn [dn _]
     (fn [evt val]
       (enqueue ch1 [evt val])
       (when (= :request evt)
         (dn :response [100]))

       (if (request-done? evt val)
         (dn :response [200 {"content-length" "5"} (buffer "Hello")])))))

  (connect
   (fn [dn _]
     (doseq* [args (seq ch3)]
       (apply dn args))
     (fn [evt val]
       (enqueue ch2 [evt val])

       (when (= :open evt)
         (dn :request [{:path-info       "/"
                        :request-method  "POST"
                        "content-length" "5"
                        "expect"         "100-continue"} :chunked]))))
   {:host "localhost" :port 4040})

  (is (next-msgs ch1 :request [#(includes-hdrs {"expect" "100-continue"} %) :chunked]))
  (is (next-msgs
       ch2
       :open     :dont-care
       :response [100 {:http-version [1 1]} nil]))

  (is (no-msgs ch1 ch2))

  (enqueue ch3
           [:body (buffer "Hello")]
           [:body nil])

  (is (next-msgs
       ch1
       :body "Hello"
       :body nil))

  (is (next-msgs
       ch2
       :response [200 {:http-version [1 1] "content-length" "5"} "Hello"])))

;; (defcoretest issuing-immediate-abort)
;; (defcoretest defaults-to-port-80
;; (defcoretest connecting-to-an-invalid-server)

;; TODO:
;; * Responses with no content-length, TE, or connection: closed

;; ==== Higher level of abstraction tests

;; (defcoretest simple-deferred-response-requests
;;   [ch1]
;;   (start-hello-world-app ch1)

;;   (doseq [method ["GET" "POST" "PUT" "DELETE"]]
;;     (is (= @(request method "http://localhost:4040/")
;;            [200
;;             {:http-version [1 1]
;;              "content-length" "5"
;;              "content-type"   "text/plain"
;;              "connection"     "close"}
;;             (buffer "Hello")]))

;;     (is (next-msgs
;;          ch1
;;          :request [{:http-version   [1 1]
;;                     :script-name    ""
;;                     :path-info      "/"
;;                     :query-string   ""
;;                     :request-method method
;;                     :remote-addr    :dont-care
;;                     :local-addr     :dont-care
;;                     "host"          "localhost"} nil]
;;          :done nil))))

;; (defcoretest passing-headers-to-deferred-request
;;   [ch1]
;;   (start-hello-world-app ch1)

;;   (GET "http://localhost:4040/foo" {"x-foo" "barbar"})

;;   (is (next-msgs
;;        ch1
;;        :request [#(includes-hdrs {:path-info "/foo" "x-foo" "barbar"} %) nil])))

;; (defcoretest connecting-without-uri-string
;;   [ch1]
;;   (start-hello-world-app ch1)

;;   (GET {:host "localhost" :port 4040 :path-info "/bar"})

;;   (is (next-msgs
;;        ch1
;;        :request [#(includes-hdrs {:path-info "/bar"} %) nil])))

;; (defcoretest errors-abort-response-with-async-val-api
;;   nil

;;   (is (thrown-with-msg? Exception #"Connection refused"
;;         @(GET "http://localhost:4040"))))

;; (defcoretest simple-cases-with-async-val-api
;;   [ch1]
;;   (start-hello-world-app ch1)

;;   (GET "http://localhost:4040")

;;   (is (next-msgs
;;        ch1
;;        :request [#(includes-hdrs {:path-info "/" "host" "localhost"} %) nil])))

;; (defcoretest handling-chunked-response-bodies-with-async-val-api
;;   [ch1]
;;   (server/start
;;    (fn [dn _]
;;      (fn [evt val]
;;        (when (= :request evt)
;;          (dn :response [200 {"transfer-encoding" "chunked"} :chunked])
;;          (dn :body (buffer "Hello"))
;;          (dn :body (buffer "World"))
;;          (dn :body (buffer "these are"))
;;          (dn :body (buffer "chunks"))
;;          (dn :body nil)))))

;;   (let [[status hdrs body] @(GET "http://localhost:4040/")]
;;     (is (= 200 status))
;;     (is (= (hdrs "transfer-encoding") "chunked"))
;;     (is (seq? body))
;;     (is (= ["Hello" "World" "these are" "chunks"]
;;            @(doasync (join body [])
;;               (fn [[chunk & more :as chunks] aggregated]
;;                 (if chunks
;;                   (recur* (join more (conj aggregated (to-string chunk))))
;;                   aggregated)))))))

;; (defcoretest handling-request-bodies-with-async-val-api
;;   [ch1]
;;   (start-hello-world-app ch1)

;;   (GET "http://localhost:4040/" {"content-length" "5"} "Hello")

;;   (is (next-msgs
;;        ch1
;;        :request [#(includes-hdrs {"content-length" "5"} %) "Hello"]
;;        :done    nil))

;;   (GET "http://localhost:4040/"
;;     {"transfer-encoding" "chunked"}
;;     ["Hello" "world" "these" "are" "some" "chunks"])

;;   (is (next-msgs
;;        ch1
;;        :request [#(includes-hdrs {"transfer-encoding" "chunked"} %) :chunked]
;;        :body    "Hello"
;;        :body    "world"
;;        :body    "these"
;;        :body    "are"
;;        :body    "some"
;;        :body    "chunks"
;;        :body    nil
;;        :done    nil))

;;   (let [ch (channel)]
;;     (future
;;       (doseq [chunk ["Hello" "world" "these" "are" "some" "chunks"]]
;;         (Thread/sleep 10)
;;         (put ch chunk))
;;       (close ch))

;;     (GET "http://localhost:4040/"
;;       {"transfer-encoding" "chunked"}
;;       (seq ch))

;;     (is (next-msgs
;;          ch1
;;          :request [#(includes-hdrs {"transfer-encoding" "chunked"} %) :chunked]
;;          :body    "Hello"
;;          :body    "world"
;;          :body    "these"
;;          :body    "are"
;;          :body    "some"
;;          :body    "chunks"
;;          :body    nil
;;          :done    nil))))

;; If test fails, make sure max time not reached
;; (defcoretest ^{:network true} pause-resume-with-async-val-api
;;   [ch1 ch2 ch3]
;;   ;; Scumbag server
;;   (server/start
;;    (fn [dn _]
;;      (doasync (seq ch3)
;;        (fn [[args]]
;;          (apply dn args)))
;;      (fn [evt val]
;;        (when (= :abort evt)
;;          (.printStackTrace val))
;;        (when (= :request evt)
;;          (dn :pause nil))

;;        (when (= :body evt)
;;          (if val
;;            (enqueue ch1 val)
;;            (do
;;              (close ch1)
;;              (dn :response [200 {"content-length" "5"} (buffer "Hello")]))))))
;;    {:timeout 120})

;;   (future
;;     (dotimes [_ 5]
;;       (Thread/sleep 700)
;;       (dotimes [_ 10000]
;;         (put ch2 (buffer "HAMMER TIME!"))))
;;     (close ch2)

;;     (ch3 [:resume nil]))

;;   (POST "http://localhost:4040/" {"transfer-encoding" "chunked"} (seq ch2))

;;   (let [actual (buffer (blocking (seq ch1)))
;;         times  (* 5 10000)
;;         length (* times 12)]

;;     (is (= (remaining actual) length))
;;     (is (= actual (buffer (repeatedly times #(buffer "HAMMER TIME!")))))))

;; (defcoretest exception-handling-request-body
;;   [ch1]
;;   (server/start
;;    (fn [dn _]
;;      (fn [evt val]
;;        (enqueue ch1 [evt val]))))

;;   (POST "http://localhost:4040/"
;;     {"transfer-encoding" "chunked"}
;;     (async-seq
;;       (cons (buffer "Hello")
;;             (async-seq
;;               (throw (Exception. "BOOM"))))))

;;   (Thread/sleep 100)

;;   (is (next-msgs
;;        ch1
;;        :request :dont-care
;;        :body    "Hello"
;;        :abort   :dont-care)))

;; (defcoretest early-response-body-termination
;;   [ch1]
;;   (server/start
;;    (fn [dn _]
;;      (fn [evt val]
;;        (enqueue ch1 [evt val])
;;        (when (= :request evt)
;;          (dn :response [200 {"transfer-encoding" "chunked"} :chunked])
;;          (dn :body (buffer "Hello"))))))

;;   (let [[_ _ body] @(GET "http://localhost:4040/")
;;         body @body more (next body)]

;;     (is (= (buffer "Hello") (first body)))

;;     (is (= true (interrupt more)))

;;     (is (next-msgs
;;          ch1
;;          :request :dont-care
;;          :abort   #(instance? IOException %)))))

;; (defcoretest exception-handling-request-body)
;; Sending request w/ content-length and invalid body length
