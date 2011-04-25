(ns picard.test.server
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

(defn call-home-app
  [ch]
  (fn [resp]
    (fn [evt val]
      (enqueue ch [evt val])
      (when (= evt :done)
        (resp :respond [200 {"content-type" "text/plain" "content-length" "6"}])
        (resp :body "Hello\n")
        (resp :done nil)))))

(defn connect
  [f]
  (let [sock (Socket. "127.0.0.1" 4040)]
    (try
      (f (.getInputStream sock) (.getOutputStream sock))
      (finally
       (.close sock)))))

(declare ch in out)

(defmacro running-call-home-app
  [& stmts]
  `(let [ch# (channel)
         stop-fn# (server/start (call-home-app ch#))]
     (Thread/sleep 100)
     (try
       (connect
        (fn [in# out#]
          (binding [ch  ch#
                    in  in#
                    out out#]
            ~@stmts)))
       (finally
        (stop-fn#)))))

(defn write
  [& strs]
  (doseq [s strs]
    (.write out (.getBytes s))))

(defn normalize-msg
  [[evt val]]
  (if (instance? ChannelBuffer val)
    [evt (.toString val "UTF-8")]
    [evt val]))

(defn next-msg-is
  [evt val]
  (is (= [evt val] (normalize-msg (wait-for-message ch)))))

(defn resp-is
  [& strs]
  (let [http (apply str strs)
        in   in
        resp (future
               (let [stream (repeatedly (count http) #(.read in))]
                 (apply str (map char stream))))]
    (is (= http
           (.get resp 50 TimeUnit/MILLISECONDS)))))

(deftest simple
  (running-call-home-app
   (write "GET / HTTP/1.1\r\n\r\n")
   (next-msg-is
    :headers {:server-name "Picard"
              :script-name ""
              :path-info "/"
              :request-method "GET"})
   (next-msg-is :body "")
   (next-msg-is :done nil)
   (resp-is "HTTP/1.1 200 OK\r\n")))
