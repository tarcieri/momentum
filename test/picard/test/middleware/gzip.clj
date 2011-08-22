(ns picard.test.middleware.gzip
  (:use
   [clojure.test]
   [picard.helpers]
   [picard.test])
  (:require
   [picard.middleware.gzip :as gzip])
  (:import
   [java.io
    ByteArrayInputStream
    ByteArrayOutputStream]
   [java.util.zip
    GZIPInputStream]
   [org.jboss.netty.buffer
    ChannelBuffer
    ChannelBuffers]))

(defn- ungzip-body
  [body]
  (let [bytes (gzip/body->byte-array body)
        bais (ByteArrayInputStream. bytes)
        gzip-input-stream (GZIPInputStream. bais)
        buf (byte-array 4092)
        byte-array-output-stream (ByteArrayOutputStream.)]

    (loop []
      (let [bytes-read (.read gzip-input-stream buf 0 4092)]
        (if-not (= -1 bytes-read)
          (do
            (.write byte-array-output-stream buf 0 bytes-read)
            (recur))
          (String. (.toByteArray byte-array-output-stream)))))))

(defn- simple-test-app
  [downstream]
  (defstream
    (request [req]
      (downstream :response [200 {"content-type" "text/html""content-length" "11"} "hello world"]))))

(deftest simple-gzip-test
  (with-app (gzip/encoder simple-test-app)
    (GET "/" {"accept-encoding" "gzip"})
    (let [[_ _ body] (-> (last-exchange)
                         exchange-events
                         first
                         second)]
      (is (= "hello world" (ungzip-body body)))
      (is (= "gzip" ((last-response-headers) "content-encoding")))
      (is (= 200 (last-response-status))))))

(deftest doesnt-gzip-without-header
  (with-app (gzip/encoder simple-test-app)
    (GET "/" {})
    (is (not (= "gzip" ((last-response-headers) "content-encoding"))))
    (is (= "hello world" (last-response-body)))
    (is (= 200 (last-response-status)))))

(defn- head-request-test-app
  [downstream]
  (defstream
    (request [req]
      (downstream :response [200 {"content-type" "text/html""content-length" "11"} nil]))))

(deftest doesnt-explode-on-head-request
  (with-app (gzip/encoder head-request-test-app)
    (HEAD "/" {"accept-encoding" "gzip"})
    (is (not (= "gzip" ((last-response-headers) "content-encoding"))))
    (is (= 200 (last-response-status)))))

(defn- chunked-test-app
  [downstream]
  (defstream
    (request [req]
      (downstream :response [200 {"content-type" "text/html" "transfer-encoding" "chunked"} :chunked])
      (downstream :body "hello")
      (downstream :body " world")
      (downstream :body "!\n")
      (downstream :body nil))))

(deftest chunked-gzip-test
  (with-app (gzip/encoder chunked-test-app)
    (GET "/" {"accept-encoding" "gzip"})

    (let [baos (ByteArrayOutputStream.)
          events (exchange-events (last-exchange))
          buf (byte-array 4092)
          body-events (filter #(and (not (nil? (second %))) (= :body (first %)))  events)]

      ;; write all the chunks into the baos
      (doseq [body body-events]
        (.write baos (gzip/body->byte-array (second body))))
      (.close baos)

      ;; read from the input stream
      (let [bais (ByteArrayInputStream. (.toByteArray baos))
            gzip-input-stream (GZIPInputStream. bais)
            baos2 (ByteArrayOutputStream.)]
       (loop []
         (let [bytes-read (.read gzip-input-stream buf 0 4092)]
           (if-not (= -1 bytes-read)
             (do
               (.write baos2 buf 0 bytes-read)
               (recur))
             (is (= "hello world!\n" (String. (.toByteArray baos2)))))))))))

(defn- nonsupported-content-type-app
  [downstream]
  (defstream
    (request [req]
      (downstream :response [200 {"content-type" "application/crazy""content-length" "11"} "hello world"]))))

(deftest doesnt-gzip-without-appropriate-content-type
  (with-app (gzip/encoder nonsupported-content-type-app)
    (GET "/" {"accept-encoding" "gzip"})
    (is (not (= "gzip" ((last-response-headers) "content-encoding"))))
    (is (= "hello world" (last-response-body)))
    (is (= 200 (last-response-status)))))

(defn- content-length-chunked-test-app
  [downstream]
  (defstream
    (request [req]
      (downstream :response [200 {"content-type" "text/html"
                                  "content-length" "13"} :chunked])
      (downstream :body "hello")
      (downstream :body " world")
      (downstream :body "!\n")
      (downstream :body nil))))

(deftest content-length-chunked-test
  (with-app (gzip/encoder content-length-chunked-test-app)
    (GET "/" {"accept-encoding" "gzip"})
    (let [[_ _ body] (-> (last-exchange)
                         exchange-events
                         first
                         second)]
      (is (= "hello world!\n" (ungzip-body body)))
      (is (= "gzip" ((last-response-headers) "content-encoding")))
      (is (= 200 (last-response-status))))))

(deftest doesnt-buffer-if-content-length-too-large
  (with-app (gzip/encoder content-length-chunked-test-app {:max-buffer-bytes 1})
    (GET "/" {"accept-encoding" "gzip"})
    (is (= 200 (last-response-status)))
    (is (= :chunked (last-response-body)))))

(defn- lies-about-content-length-app
  [downstream]
  (defstream
    (request [req]
      (downstream :response [200 {"content-type" "text/html"
                                  "content-length" "5"} :chunked])
      (downstream :body "hello")
      (downstream :body " world")
      (downstream :body "!\n")
      (downstream :body nil))))

(deftest blows-up-if-app-lies-about-content-length
  (with-app (gzip/encoder lies-about-content-length-app {:max-buffer-bytes 10})
    (GET "/" {"accept-encoding" "gzip"})
    (is (= 500 (last-response-status)))))
