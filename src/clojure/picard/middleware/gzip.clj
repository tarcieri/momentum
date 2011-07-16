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

(defn- gzip-entire-body
  [body]
  (let [deflater (Deflater. Deflater/DEFAULT_COMPRESSION true)
        crc (CRC32.)
        baos (ByteArrayOutputStream.)
        buf (byte-array 4092)
        bytes (body->byte-array body)]
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

(def default-opts {:gzip-content-types #{"text/plain"
                                         "text/html"
                                         "text/xhtml"
                                         "text/javascript"
                                         "application/javascript"
                                         "text/css"}})

(defn encoder
  ([app] (encoder app default-opts))
  ([app opts]
     (defmiddleware
       [state downstream upstream]
       app

       :upstream
       (defstream
         (request [[{accept-encoding-header "accept-encoding"} _ :as req]]
           ;; save off whether we accept gzip for this request and
           ;; pass upstream
           (let [accept-encoding-header (or accept-encoding-header "")
                 accept-encodings (into #{} (string/split #"\s*,\s*" (string/trim accept-encoding-header)))
                 accept-gzip? (contains? accept-encodings "gzip")]
             (swap! state #(assoc % :accept-gzip? accept-gzip?))
             (upstream :request req)))

         ;; must pass all other events through, like pause/resume/abort
         (else [evt val]
           (upstream evt val)))

       :downstream
       (defstream
         (response [[status {content-type-header "content-type" :as hdrs} body :as resp]]
           ;; send the resposne with appropriate headers
           (let [content-type-header (content-type hdrs)
                 gzip-content-type? (contains? (:gzip-content-types opts) content-type-header)]
             (if-not (and gzip-content-type? (:accept-gzip? @state))
               (downstream :response resp)
               (let [hdrs (assoc hdrs "content-encoding" "gzip")]
                 (if (= :chunked body)
                   ;; chunked body, yank the content length header,
                   ;; add the transfer encoding header, and set the
                   ;; state so that chunks will gzip and allocate a
                   ;; deflater, crc, and buffer for the exchange
                   (let [hdrs (-> hdrs (assoc "transfer-encoding" "chunked") (dissoc "content-length"))]
                     (swap! state #(assoc %
                                     :gzip? true
                                     :deflater (Deflater. Deflater/DEFAULT_COMPRESSION true)
                                     :crc (CRC32.)
                                     :buf (byte-array 4092)))
                     (downstream :response [status hdrs body]))

                   ;; not chunked, gzip the whole thing and send it down
                   (let [gzipped-body (gzip-entire-body body)
                         hdrs (assoc hdrs "content-length" (.readableBytes gzipped-body))]
                     (downstream :response [status hdrs gzipped-body])))))))

         (body [chunk]
           (if-not (:gzip? @state)
             ;; not gziping, send chunk as is
             (downstream :body chunk)

             ;; gzip and send appropriate chunks
             (let [deflater (:deflater @state)
                   crc (:crc @state)
                   buf (:buf @state)]
               (gzip-and-send-chunk state downstream chunk deflater crc buf))))

         ;; must pass all other events through, like pause/resume/abort
         (else [evt val]
           (downstream evt val))))))
