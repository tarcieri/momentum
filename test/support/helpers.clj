(ns support.helpers
  (:use
   clojure.test
   support.string
   momentum.core)
  (:require
   [momentum.net.server :as server])
  (:import
   [momentum.async
    TimeoutException]
   [org.jboss.netty.buffer
    ChannelBuffer]
   [java.nio
    ByteBuffer]
   [java.net
    Socket]
   [java.util.concurrent
    TimeUnit]))

(declare
 ^:dynamic ch1
 ^:dynamic ch2
 ^:dynamic ch3
 ^:dynamic ch4
 ^:dynamic sock
 ^:dynamic in
 ^:dynamic out
 ^:dynamic server)

(defn- stop-servers
  [servers]
  (if (sequential? servers)
    (doseq [server servers]
      (server/stop server))
    (server/stop servers)))

(defn- close-channels
  []
  (doseq [ch [ch1 ch2 ch3 ch4]]
    (close ch)))

(defn with-core-test-context
  [name start-server f]
  (println name)
  (let [server (start-server)]
    (if server
      (try
        (binding [server server]
          (f))
        (finally
         (close-channels)
         (stop-servers server)))
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

(defn closed-socket?
  []
  (not (open-socket?)))

(defn- read-byte
  [in]
  (try
    (.get (future (.read in))
          200 TimeUnit/MILLISECONDS)
    (catch java.util.concurrent.TimeoutException _
      -1)))

(defn read-socket
  ([] (read-socket in))
  ([in] (read-socket in 1000))
  ([in timeout]
     (lazy-seq
      (let [byte (read-byte in)]
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
  (.write out (.getBytes (apply str strs)))
  (.flush out))

(defn normalize
  [val]
  (try
    (cond
     (vector? val)
     (vec (map normalize val))

     (map? val)
     (into {} (map (comp vec normalize vec) val))

     (buffer? val)
     (to-string val)

     (instance? ChannelBuffer val)
     (.toString val "UTF-8")

     (instance? ByteBuffer val)
     (let [val (.duplicate val)
           arr (byte-array (.remaining val))]
       (.get val arr)
       (String. arr))

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

(defn includes-hdrs
  [a b]
  (= a (select-keys b (keys a))))

;; === Matchers

(defn- blocking
  [seq]
  (when seq
    (lazy-seq
     (let [s (deref seq 2000 ::timeout)]
       (cond
        (= ::timeout s)
        (list ::timeout)

        s
        (cons (normalize (first s)) (blocking (next s))))))))

(defn assert-no-msgs-for
  [f msg chs]
  (Thread/sleep 50)
  (let [received (into {} (filter (fn [[_ ch]] (not= 0 (count ch))) chs))]
    (f {:type     (if (empty? received) :pass :fail)
        :message  msg
        :expected []
        :actual   received})))

(defn assert-next-msgs
  [f msg ch & expected]
  (when (odd? (count expected))
    (throw (IllegalArgumentException. "Requires even number of messages")))

  (let [expected (partition 2 expected)
        actual   (take (count expected) (blocking (seq ch)))
        pass?    (every? identity (map #(match-values (vec %1) %2) expected actual))]
    (f {:type     (if pass? :pass :fail)
        :message  msg
        :expected expected
        :actual   actual})))

(defmethod assert-expr 'next-msgs
  [msg form]
  (let [[_ ch & stmts] form]
    `(assert-next-msgs #(do-report %) ~msg ~ch ~@stmts)))

(defmethod assert-expr 'no-msgs [msg form]
  (let [[_ & args] form
        chs (zipmap (map str args) args)]
    `(assert-no-msgs-for #(do-report %) ~msg ~chs)))

(defn- read-n-as-str
  [n]
  (->> (read-socket in)
       (take n)
       (map char)
       (apply str)))

(defn- segment-len
  [segment]
  (if (coll? segment)
    (reduce + (map count segment))
    (count segment)))

(defn- segments-len
  [segments]
  (reduce + (map segment-len segments)))

(defn segment-match?
  [segment str]
  (if (coll? segment)
    (substrings? segment str)
    (= segment str)))

(defn- segments-match?
  [segments str]
  (loop [[segment & rest] segments str str]
    (if (and segment (seq str))
      (let [len (segment-len segment)]
        (when (segment-match? segment (subs str 0 len))
          (recur rest (subs str len))))
      ;; Otherwise, make sure the segment is nil and the string is
      ;; empty, aka both the expected value and the actual value have
      ;; been walked to their conclusions.
      (if segment
        (and (empty? rest) (= segment str))
        (empty? str)))))

(defn assert-receiving
  [f msg & segments]
  (let [len (segments-len segments)
        act (read-n-as-str len)
        eq? (segments-match? segments act)]
    (f
     {:type     (if eq? :pass :fail)
      :message  msg
      :expected segments
      :actual   act})))

(defmethod assert-expr 'receiving [msg form]
  (let [expected (rest form)]
    `(assert-receiving #(do-report %) ~msg ~@expected)))
