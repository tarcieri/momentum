(ns picard.middleware.gzip
  (:use
   [picard.helpers])
  (:require
   [clojure.contrib.string :as string])
  (:import
   [picard
    GzipUtils]
   [java.util.zip
    CRC32
    Deflater]
   [java.io
    ByteArrayOutputStream]
   [org.jboss.netty.buffer
    ChannelBuffer
    ChannelBuffers]))

(def header-length (alength GzipUtils/header))
(def trailer-length 8)

(defn body->byte-array
  [body]
  (cond
   (instance? ChannelBuffer body)
   (let [len (.capacity body)
         buf (byte-array len)]
     (.getBytes body 0 buf)
     buf)

   (instance? String body)
   (.getBytes body "UTF-8")

   :else
   (throw (Exception. "unsupported body type!"))))

(defn- mk-trailer
  [deflater crc]
  (let [buf (byte-array trailer-length)]
    (GzipUtils/writeTrailer deflater crc buf 0)
    buf))

(defn- gzip-byte-array
  [bytes]
  (let [deflater (Deflater. Deflater/DEFAULT_COMPRESSION true)
        crc (CRC32.)
        baos (ByteArrayOutputStream.)
        buf (byte-array 4092)]
    ;; write header
    (.write baos GzipUtils/header)

    ;; update the crc
    (.update crc bytes 0 (alength bytes))

    ;; set up the deflater
    (.setInput deflater bytes)
    (.finish deflater)

    ;; read all the compressed bytes
    (loop []
      (let [bytes-read (.deflate deflater buf)]
        (if-not (= 0 bytes-read)
          (do
            (.write baos buf 0 bytes-read)
            (recur))
          (do
            (.write baos (mk-trailer deflater crc))
            (.close baos)
            (ChannelBuffers/copiedBuffer (.toByteArray baos))))))))

(defn- gzip-entire-body
  [body]
  (gzip-byte-array (body->byte-array body)))

(defn- send-chunks
  [deflater downstream buf]
  (let [buf-len (alength buf)]
    (loop []
      (let [compressed-bytes (.deflate deflater buf 0 buf-len)]
        (when-not (= 0 compressed-bytes)
          (let [channel-buffer (ChannelBuffers/copiedBuffer buf 0 compressed-bytes)]
            (downstream :body channel-buffer)
            (recur)))))))

(defn- gzip-and-send-chunk
  [state downstream chunk deflater crc buf]
  (let [buffer-length (alength buf)
        sent-header (:sent-header @state)]
    ;; update the crc and set the input
    (if (nil? chunk)
      (.finish deflater)
      (let [bytes (body->byte-array chunk)]
        (.update crc bytes 0 (alength bytes))
        (.setInput deflater bytes)))

    ;; send the header if needed
    (when-not sent-header
      (swap! state #(assoc % :sent-header true))
      (downstream :body (ChannelBuffers/wrappedBuffer GzipUtils/header)))

    ;; send the chunks
    (send-chunks deflater downstream buf)

    ;; send the trailer if needed
    (when (nil? chunk)
      (downstream :body (ChannelBuffers/wrappedBuffer (mk-trailer deflater crc)))
      (downstream :body nil))))

(defn- handle-request
  ;; save off whether we accept gzip for this request and
  ;; pass upstream
  [state upstream [hdrs body :as req]]
  (let [accept-gzip? (contains? (accept-encodings hdrs) "gzip")]
    (swap! state #(assoc % :accept-gzip? accept-gzip?))
    (upstream :request req)))

(defn- handle-response
  [opts state downstream [status hdrs body :as resp]]
  (let [content-type-header (content-type hdrs)
        gzip-content-type? (contains? (:gzip-content-types opts) content-type-header)]
    (if-not (and gzip-content-type? (:accept-gzip? @state))
      ;; don't gzip
      (downstream :response resp)

      ;; gzip
      (let [hdrs (assoc hdrs "content-encoding" "gzip")
            chunked? (= :chunked body)
            content-bytes (content-length hdrs)]
        (cond
         ;; chunked, but a content-length is specified and it is an acceptable
         ;; size to buffer this up and gzip it in one go
         (and content-bytes (<= content-bytes (:max-buffer-bytes opts)) chunked?)
         (swap! state #(assoc %
                         :aborted? false
                         :body-action :gzip-buffer
                         :status status
                         :hdrs hdrs
                         :bytes-buffered 0
                         :baos (ByteArrayOutputStream. content-bytes)))

         ;; chunked body, yank the content length header,
         ;; add the transfer encoding header, and set the
         ;; state so that chunks will gzip and allocate a
         ;; deflater, crc, and buffer for the exchange
         chunked?
         (let [hdrs (-> hdrs (assoc "transfer-encoding" "chunked") (dissoc "content-length"))]
           (swap! state #(assoc %
                           :body-action :gzip-stream
                           :deflater (Deflater. Deflater/DEFAULT_COMPRESSION true)
                           :crc (CRC32.)
                           :buf (byte-array 4092)))
           (downstream :response [status hdrs body]))

         ;; not chunked, gzip the whole thing and send it down
         :else
         (let [gzipped-body (gzip-entire-body body)
               hdrs (assoc hdrs "content-length" (.readableBytes gzipped-body))]
           (downstream :response [status hdrs gzipped-body])))))))

(def response-body-too-large-error
  [500 {"content-length"     "0"
        "x-picard-error-msg" "Response body too large"} nil])

(defn- handle-chunk
  [opts state downstream chunk]
  (when-not (:aborted? @state)
    (case
     (:body-action @state)

     :gzip-stream
     (let [{deflater :deflater crc :crc buf :buf} @state]
       (gzip-and-send-chunk state downstream chunk deflater crc buf))

     :gzip-buffer
     (if (nil? chunk)
       ;; last chunk, we're all done, gzip it and send it down
       (let [{baos :baos status :status hdrs :hdrs} @state
             gzipped-body (gzip-byte-array (.toByteArray baos))
             hdrs (assoc hdrs "content-length" (.readableBytes gzipped-body))]
         (downstream :response [status hdrs gzipped-body]))

       ;; valid chunk, check that we're within our size limit
       ;; and buffer the bytes
       (let [bytes (body->byte-array chunk)
             {baos :baos bytes-buffered :bytes-buffered} @state
             bytes-buffered (+ bytes-buffered (alength bytes))]
         (if (> (:max-buffer-bytes opts) bytes-buffered)
           (do
             (swap! state #(assoc % :bytes-buffered bytes-buffered))
             (.write baos bytes))
           (do
             (swap! state #(assoc % :aborted? true))
             (downstream :response response-body-too-large-error)))))

     ;; otherwise nothing
     (downstream :body chunk))))

(def default-opts {:max-buffer-bytes 8192
                   :gzip-content-types #{"text/plain"
                                         "text/html"
                                         "text/xhtml"
                                         "text/javascript"
                                         "application/javascript"
                                         "text/css"}})

(defn encoder
  ([app] (encoder app {}))
  ([app opts]
     (let [opts (merge default-opts opts)]
      (defmiddleware
        [state downstream upstream]
        app
        :upstream
        (defstream
          (request [req] (handle-request state upstream req))
          (else [evt val] (upstream evt val)))
        :downstream
        (defstream
          (response [resp] (handle-response opts state downstream resp))
          (body [chunk] (handle-chunk opts state downstream chunk))
          (else [evt val] (downstream evt val)))))))
