(ns picard.http.multipart
  (use
   picard.core.buffer)
  (:import
   [picard.http
    MultipartParser
    MultipartParserCallback]))

(defn- mk-callback
  [f]
  (reify MultipartParserCallback
    (blankHeaders [_]
      (transient {}))

    (header [_ headers name value]
      (let [existing (headers name)]
        (cond
         (nil? existing)
         (assoc! headers name value)

         (string? existing)
         (assoc! headers name [existing value])

         :else
         (assoc! headers name (conj existing value)))))

    (part [_ hdrs body]
      (f :part [(persistent! hdrs) (or body :chunked)]))

    (chunk [_ chunk]
      (f :body chunk))

    (done [_]
      (f :part nil))))

(defn parser
  [f boundary]
  (let [parser (MultipartParser. (buffer boundary) (mk-callback f))]
    (fn [buf] (.execute parser (buffer buf)))))
