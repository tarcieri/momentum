(ns support.helpers
  (:use
   clojure.test)
  (:require
   [lamina.core :as l]
   [picard.net.server :as server])
  (:import
   [org.jboss.netty.buffer
    ChannelBuffer]
   [java.net
    Socket]
   [java.util.concurrent
    TimeoutException
    TimeUnit]))

(declare ch1 ch2 ch3 ch4 sock in out server)

(def channel l/channel)
(def enqueue l/enqueue)
(def receive l/receive)

(defn with-core-test-context
  [name start-server f]
  (println name)
  (let [server (start-server)]
    (if server
      (try
        (binding [server server]
          (f))
        (finally (server/stop server)))
      (f))))

(defmacro defcoretest
  ([name start-server test] `(defcoretest ~name [] ~start-server ~test))
  ([name bindings start-server & body]
     (cond
      (not (vector? bindings))
      `(defcoretest ~name [] ~bindings ~start-server ~@body)

      :else
      `(deftest ~name
         (binding [ch1 (channel) ch2 (channel) ch3 (channel) ch4 (channel)]
           (let [~bindings [ch1 ch2 ch3 ch4]]
             (with-core-test-context
               ~(str name)
               (fn [] ~start-server)
               (fn [] ~@body))))))))

(defn socket-connect
  ([f] (socket-connect f 4040))
  ([f port]
     (let [sock (Socket. "127.0.0.1" port)]
       (let [in (.getInputStream sock) out (.getOutputStream sock)]
         (try
           (f sock in out)
           (finally
            (when-not (.isClosed sock)
              (.close sock))))))))

(defmacro with-socket
  [& body]
  `(socket-connect
    (fn [sock# in# out#]
      (binding [sock sock# in in# out out#]
        ~@body))))

(defn close-socket
  []
  (.close sock))

(defn open-socket?
  []
  (let [byte (.read in)]
    (if (= 0 byte)
      (recur)
      (< 0 byte))))

(defn read-socket
  ([] (read-socket in))
  ([in] (read-socket in 1000))
  ([in timeout]
     (lazy-seq
      (let [byte (.read in)]
        (if (<= 0 byte)
          (cons byte (read-socket in))
          [])))))

(defn flush-socket
  []
  (.flush out))

(defn drain-socket
  []
  (loop []
    (when (<= 0 (.read in))
      (recur))))

(defn write-socket
  [& strs]
  (doseq [s strs]
    (.write out (.getBytes s)))
  (.flush out))

(defn next-msg
  ([] (next-msg ch1))
  ([ch] (l/wait-for-message ch 500)))

(defn normalize
  [val]
  (try
    (cond
     (vector? val)
     (vec (map normalize val))

     (map? val)
     (into {} (map (comp vec normalize vec) val))

     (instance? ChannelBuffer val)
     (.toString val "UTF-8")

     :else
     val)
    (catch Exception e (.printStackTrace e))))

(defn match-values
  [val val*]
  (cond
   (= val :dont-care)
   true

   (set? val)
   ((first val) val*)

   (and (map? val) (= (count val) (count val*)))
   (every? (fn [[k v]] (match-values v (val* k))) val)

   (and (vector? val) (vector? val*) (= (count val) (count val*)))
   (every? #(apply match-values %) (map vector val val*))

   :else
   (or (= val val*)
       (and (fn? val) (val val*)))))

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

;; === Matchers

(defmethod assert-expr 'next-msgs [msg form]
  (let [[_ ch & stmts] form]
    (next-msgs-for ch msg stmts)))

(defmethod assert-expr 'receiving [msg form]
  (let [expected (rest form)]
    `(let [in#       in
           expected# (str ~@expected)
           actual#   (.get (future (->> (read-socket in#)
                                        (take (count expected#))
                                        (map char)
                                        (apply str)))
                           200 TimeUnit/MILLISECONDS)]
       (if (= expected# actual#)
         (do-report {:type :pass :message ~msg
                     :expected expected# :actual actual#})
         (do-report {:type :fail :message ~msg
                     :expected expected# :actual actual#})))))
