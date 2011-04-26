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
    TimeUnit]))

;; ### TEST APPLICATIONS
(defn call-home-app
  [ch]
  (fn [resp]
    (enqueue ch [:binding nil])
    (fn [evt val]
      (enqueue ch [evt val])
      (when (= evt :done)
        (resp :respond [200 {"content-type" "text/plain"
                             "content-length" "6"}])
        (resp :body "Hello\n")
        (resp :done nil)))))

;; ### HELPER FUNCTIONS
(defn connect
  ([f] (connect f 4040))
  ([f port]
     (let [sock (Socket. "127.0.0.1" port)]
       (try
         (f (.getInputStream sock) (.getOutputStream sock))
         (finally (.close sock))))))

(declare ch in out)

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
  []
  (repeatedly #(.read in)))

(defn http-request
  [method path hdrs]
  (http-write method " " path " HTTP/1.1\r\n\r\n"))

(defn http-get
  ([path] (http-get path {}))
  ([path hdrs]
     (http-request "GET" path hdrs)))

(defn normalize-body
  [val]
  (if (instance? ChannelBuffer) (.toString val "UTF-8") val))

(defn normalize-req
  [[evt val :as req]]
  (cond
   (= evt :request)
   (let [[hdrs body] val]
     [evt [hdrs (normalize-body body)]])

   (= evt :body)
   [evt (normalize-body val)]

   :else
   req))

(defn next-msg
  []
  (wait-for-message ch 50))

(defn match-values
  [val val*]
  (cond
   (set? val)
   ((first val) val*)

   (and (vector? val) (= (count val) (count val*)))
   (every? #(apply match-values %) (map vector val val*))

   :else
   (= val val*)))

(defn next-msg-is
  ([evt] (next-msg-is evt nil))
  ([evt val]
     (let [[evt* val*] (next-msg)]
       (when-not (and (= evt evt*) (match-values val val*))
         (is (= [evt val] [evt* val*]))))))

(defn next-msgs-are
  [& pairs]
  (doseq [[evt val] (partition 2 pairs)]
    (next-msg-is evt val)))

(defn no-waiting-messages
  []
  (Thread/sleep 50)
  (when-not (= 0 (count ch))
    (next-msg-is [nil nil])))

(defn response-is
  [& strs]
  (let [http (apply str strs)
        in   in
        resp (future
               (let [stream (repeatedly (count http) #(.read in))]
                 (apply str (map char stream))))]
    (is (= http
           (.get resp 50 TimeUnit/MILLISECONDS)))))

(defn includes-hdrs
  [hdrs]
  #{(fn [actual]
      (= hdrs (select-keys actual (keys hdrs))))})

(defn is-req-including-hdrs
  [[evt val] hdrs]
  (is (= :request evt))
  (let [[actual-headers] val]
    (is (= hdrs (select-keys actual-headers (keys hdrs))))))

(defn next-msg-is-req-including-hdrs
  [hdrs]
  (is-req-with-hdrs (next-msg) hdrs))
