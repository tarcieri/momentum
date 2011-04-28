(ns test-helper
  (:use
   [clojure.test]
   [lamina.core])
  (:require
   [picard.server :as server])
  (:import
   [org.jboss.netty.buffer
    ChannelBuffer]
   [java.net
    Socket]
   [java.util.concurrent
    TimeoutException
    TimeUnit]))

(declare ch ch2 in out drain)

;; ### TEST APPLICATIONS
(defn call-home-app
  [ch]
  (fn [resp]
    (enqueue ch [:binding nil])
    (fn [evt val]
      (enqueue ch [evt val])
      (when (= evt :request)
        (resp :respond [200 {"content-type" "text/plain"
                             "content-length" "5"} "Hello"])))))

;; ### HELPER FUNCTIONS
(defn connect
  ([f] (connect f 4040))
  ([f port]
     (let [sock (Socket. "127.0.0.1" port)]
       (let [in (.getInputStream sock) out (.getOutputStream sock)]
         (try
           (f in out)
           (finally
            (drain in)
            (.close sock)))))))

(defn with-fresh-conn*
  [f]
  (connect (fn [in out] (binding [in in out out] (f)))))

(defmacro with-fresh-conn
  [& stmts]
  `(with-fresh-conn* (fn [] ~@stmts)))

(defn running-app*
  [app f]
  (let [stop-fn (server/start app)]
    (try
      (connect
       (fn [in out]
         (binding [in in out out] (f))))
      (finally (stop-fn)))))

(defmacro running-app
  [app & stmts]
  `(running-app* ~app (fn [] ~@stmts)))

(defn with-channels*
  [f]
  (binding [ch (channel) ch2 (channel)]
    (f ch ch2)))

(defmacro with-channels
  [args & stmts]
  `(with-channels* (fn ~args ~@stmts)))

(defmacro running-call-home-app
  [& stmts]
  `(binding [ch (channel)]
     (running-app* (call-home-app ch) (fn [] ~@stmts))))

(defn http-write
  [& strs]
  (doseq [s strs]
    (.write out (.getBytes s)))
  (.flush out))

(defn http-read
  ([] (http-read in))
  ([in]
     (lazy-seq
      (let [byte (.read in)]
        (if (<= 0 byte)
          (cons byte (http-read in))
          [])))))

(defn drain
  [in]
  (doall (http-read in)))

(defn http-request
  [method path hdrs]
  (http-write method " " path " HTTP/1.1\r\n\r\n"))

(defn http-get
  ([path] (http-get path {}))
  ([path hdrs]
     (http-request "GET" path hdrs)))

(defn normalize-body
  [val]
  (if (instance? ChannelBuffer val) (.toString val "UTF-8") val))

(defn next-msg
  []
  (wait-for-message ch 100))

(defn match-values
  [val val*]
  (cond
   (= val :dont-care)
   true

   (set? val)
   ((first val) val*)

   (and (vector? val) (= (count val) (count val*)))
   (every? #(apply match-values %) (map vector val val*))

   :else
   (= val (normalize-body val*))))

(defn response-is
  [& strs]
  (let [http (apply str strs)
        in   in
        resp (future
               (let [stream (repeatedly (count http) #(.read in))]
                 (apply str (map char stream))))]
    (is (= http
           (.get resp 100 TimeUnit/MILLISECONDS)))))

(defn includes-hdrs
  [hdrs]
  #{(fn [actual]
      (= hdrs (select-keys actual (keys hdrs))))})

(defmethod assert-expr 'next-msgs [msg form]
  (let [[_ & stmts] form]
    `(doseq [expected# (partition 2 [~@stmts])]
       (try
         (let [actual# (next-msg)]
           (if (match-values (vec expected#) actual#)
             (do-report {:type :pass :message ~msg
                         :expected expected# :actual actual#})
             (do-report {:type :fail :message ~msg
                         :expected expected# :actual actual#})))
         (catch TimeoutException e#
           (do-report {:type :fail :message ~msg
                       :expected expected# :actual "<TIMEOUT>"}))))))

(defmethod assert-expr 'not-receiving-messages [msg form]
  `(do
     (Thread/sleep 50)
     (if (= (count ch))
       (do-report {:type :pass :message ~msg
                   :expected nil :actual nil})
       (do-report {:type :fail :message ~msg
                   :expected nil :actual (next-msg)}))))

(defmethod assert-expr 'received-response [msg form]
  (let [expected (rest form)]
    `(let [in#       in
           expected# (str ~@expected)
           actual#   (.get (future (apply str (map char (http-read in#))))
                           50 TimeUnit/MILLISECONDS)]
       (if (= expected# actual#)
         (do-report {:type :pass :message ~msg
                     :expected expected# :actual actual#})
         (do-report {:type :fail :message ~msg
                     :expected expected# :actual actual#})))))
