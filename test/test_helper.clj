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
    (fn [evt val]
      (enqueue ch [evt val])
      (when (= evt :done)
        (resp :respond [200 {"content-type" "text/plain" "content-length" "6"}])
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

(defn running-app*
  [app f]
  (let [ch (channel)
        stop-fn (server/start (app ch))]
    (try
      (connect
       (fn [in out]
         (binding [ch ch in in out out] (f))))
      (finally (stop-fn)))))

(defmacro running-call-home-app
  [& stmts]
  `(running-app* call-home-app (fn [] ~@stmts)))

(defn write
  [& strs]
  (doseq [s strs]
    (.write out (.getBytes s)))
  (.flush out))

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

(defn next-msg-is
  [evt val]
  (is (= [evt val] (normalize-req (next-msg)))))

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

(defn is-req-with-hdrs
  [[evt val] hdrs]
  (is (= :request evt))
  (let [[actual-headers] val]
    (is (= hdrs (select-keys actual-headers (keys hdrs))))))

(defn next-msg-is-req-with-hdrs
  [hdrs]
  (is-req-with-hdrs (next-msg) hdrs))
