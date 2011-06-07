(ns test-helper
  (:use
   [clojure.test]
   [lamina.core :exclude [timeout]])
  (:require
   [picard.netty  :as netty]
   [picard.server :as server])
  (:import
   [org.jboss.netty.buffer
    ChannelBuffer]
   [java.net
    Socket]
   [java.util.concurrent
    TimeoutException
    TimeUnit]))

(declare ch1 ch2 ch3 netty-evts sock in out drain)

;; ### TEST APPLICATIONS
(defn tracking-middleware
  [app]
  (let [ch ch1]
    (fn [downstream]
      (let [upstream (app downstream)]
        (fn [evt val]
          (enqueue ch [evt val])
          (upstream evt val))))))

(defmacro deftrackedapp
  [args & body]
  `(tracking-middleware (fn ~args ~@body)))

(defn hello-world
  [downstream]
  (fn [evt val]
    (when (= :request evt)
      (downstream
       :response
       [200 {"content-type"   "text/plain"
             "content-length" "5"
             "connection"     "close"} "Hello"]))))

(def slow-hello-world
  (fn [downstream]
    (fn [evt val]
      (when (= :request evt)
        (send-off
         (agent nil)
         (fn [_]
           (Thread/sleep 1000)
           (downstream :response
                       [200 {"content-type"   "text/plain"
                             "content-length" "5"} "Hello"])))))))

;; ### HELPER FUNCTIONS
(defmacro bg-while
  [test & stmts]
  `(send-off
    (agent nil)
    (fn [val#]
      (loop []
        ~@stmts
        (if ~test (recur))))))

(defmacro after
  [ms & stmts]
  `(send-off
    (agent nil)
    (fn [val#]
      (Thread/sleep ~ms)
      ~@stmts)))

(defn toggle!
  [atom]
  (swap! atom (fn [val] (not val))))

(defn connect
  ([f] (connect f 4040))
  ([f port]
     (let [sock (Socket. "127.0.0.1" port)]
       (let [in (.getInputStream sock) out (.getOutputStream sock)]
         (try
           (f sock in out)
           (finally
            (when-not (.isClosed sock)
              (try (drain in) (catch Exception e)))
            (.close sock)))))))

(defn with-fresh-conn*
  [f]
  (connect (fn [sock in out] (binding [sock sock in in out out] (f)))))

(defmacro with-fresh-conn
  [& stmts]
  `(with-fresh-conn* (fn [] ~@stmts)))

(defn add-tracking-to-pipeline
  [evts pipeline]
  (.addBefore
   pipeline "handler" "track-msgs"
   (netty/upstream-stage
    (fn [_ evt]
      (if-let [err (netty/exception-event evt)]
        (.printStackTrace err))
      (swap! evts (fn [cur-evts] (conj cur-evts evt))))))
  pipeline)

(defn running-app*
  [app f]
  (if app
    (let [netty-evts    (atom [])
          pipeline-fn   #(add-tracking-to-pipeline netty-evts %)
          server        (server/start app {:pipeline-fn pipeline-fn})]
      (try
        (connect
         (fn [sock in out]
           (binding [sock sock in in out out netty-evts netty-evts]
             (f))))
        (finally (server/stop server))))
    (f)))

(defmacro defcoretest
  "Defines a picard core test"
  [name bindings app & body]
  (if-not (vector? bindings)
    `(defcoretest ~name [] ~bindings ~app ~@body)
    `(deftest ~name
       (println ~(str name))
       (binding [ch1 (channel) ch2 (channel) ch3 (channel)]
         (let [~bindings [ch1 ch2 ch3]]
           (running-app*
            ~(cond
              (= app :hello-world)
              `(tracking-middleware hello-world)
              (= app :slow-hello-world)
              `(tracking-middleware slow-hello-world)
              :else
              app)
            (fn [] ~@body)))))))

(defmacro timeout-after
  [ms & body]
  `(let [f# (future ~@body)]
     (.get f# ~ms TimeUnit/MILLISECONDS)))

(defn http-write
  [& strs]
  (doseq [s strs]
    (.write out (.getBytes s)))
  (.flush out))

(defn http-read
  ([] (http-read in))
  ([in]
     (lazy-seq
      (let [byte (timeout-after 5000 (.read in))]
        (if (<= 0 byte)
          (cons byte (http-read in))
          [])))))

(defn close-socket
  []
  (.flush out)
  (.close sock))

(defn socket-closed?
  []
  (.isClosed sock))

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

(defn normalize
  [val]
  (try
    (cond
     (vector? val)
     (map normalize val)

     (map? val)
     (into {} (map (comp vec normalize vec) val))

     (instance? ChannelBuffer val)
     (.toString val "UTF-8")

     :else
     val)
    (catch Exception e (.printStackTrace e))))

(defn next-msg
  ([] (next-msg ch1))
  ([ch] (wait-for-message ch 20000)))

(defn match-values
  [val val*]
  (cond
   (= val :dont-care)
   true

   (set? val)
   ((first val) val*)

   (and (map? val) (= (count val) (count val*)))
   (every? (fn [[k v]] (match-values v (val* k))) val)

   (and (vector? val) (= (count val) (count val*)))
   (every? #(apply match-values %) (map vector val val*))

   :else
   (or (= val val*)
       (and (fn? val) (val val*)))))

(defn response-is
  [& strs]
  (let [http (apply str strs)
        in   in
        resp (future
               (let [stream (repeatedly (count http) #(.read in))]
                 (apply str (map char stream))))]
    (is (= http
           (.get resp 100 TimeUnit/MILLISECONDS)))))

(defn cmp-with
  [f]
  #{f})

(defn includes-hdrs
  [hdrs]
  #{(fn [actual]
      (= hdrs (select-keys actual (keys hdrs))))})

(defn netty-connect-evts
  []
  (filter netty/channel-connect-event? @netty-evts))

(defn netty-exception-events
  []
  (Thread/sleep 30)
  (filter netty/exception-event @netty-evts))

(defn- next-msgs-for
  [ch msg stmts]
  `(doseq [expected# (partition 2 [~@stmts])]
     (try
       (let [actual# (normalize (next-msg ~ch))]
         (if (match-values (vec expected#) actual#)
           (do-report {:type :pass :message ~msg
                       :expected expected# :actual actual#})
           (do-report {:type :fail :message ~msg
                       :expected expected# :actual actual#})))
       (catch TimeoutException e#
         (do-report {:type :fail :message ~msg
                     :expected expected# :actual "<TIMEOUT>"})))))

(defn- no-msgs-for
  [ch msg]
  `(do
     (Thread/sleep 50)
     (if (= 0 (count ~ch))
       (do-report {:type :pass :message ~msg
                   :expected nil :actual nil})
       (do-report {:type :fail :message ~msg
                   :expected nil :actual (next-msg ~ch)}))))

(defmethod assert-expr 'next-msgs [msg form]
  (let [[_ & stmts] form]
    (next-msgs-for `ch1 msg stmts)))

(defmethod assert-expr 'next-msgs-for [msg form]
  (let [[_ ch & stmts] form]
    (next-msgs-for ch msg stmts)))

(defmethod assert-expr 'no-msgs-for [msg form]
  (let [[_ ch] form]
    (no-msgs-for ch msg)))

(defmethod assert-expr 'not-receiving-messages [msg form]
  (no-msgs-for `ch1 msg))

(defmethod assert-expr 'receiving [msg form]
  (let [expected (rest form)]
    `(let [in#       in
           expected# (str ~@expected)
           actual#   (.get (future (->> (http-read in#)
                                        (take (count expected#))
                                        (map char)
                                        (apply str)))
                           100 TimeUnit/MILLISECONDS)]
       (if (= expected# actual#)
         (do-report {:type :pass :message ~msg
                     :expected expected# :actual actual#})
         (do-report {:type :fail :message ~msg
                     :expected expected# :actual actual#})))))

(defmethod assert-expr 'received-response [msg form]
  (let [expected (rest form)]
    `(let [in#       in
           expected# (str ~@expected)
           actual#   (.get (future (apply str (map char (http-read in#))))
                           100 TimeUnit/MILLISECONDS)]
       (if (= expected# actual#)
         (do-report {:type :pass :message ~msg
                     :expected expected# :actual actual#})
         (do-report {:type :fail :message ~msg
                     :expected expected# :actual actual#})))))

(defmethod assert-expr 'closed-socket [msg form]
  `(do
     (Thread/sleep 30)
     (if (socket-closed?)
       (do-report {:type :pass :message ~msg
                   :expected true :actual true})
       (do-report {:type :fail :message ~msg
                   :expected true :actual false}))))
