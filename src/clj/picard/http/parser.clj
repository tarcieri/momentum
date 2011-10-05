(ns picard.http.parser
  (:use
   picard.utils.buffer)
  (:import
   [java.nio
    ByteBuffer]
   [picard.http
    HttpParser
    HttpParserCallback]))

(def BLANK "")

(defn- request-headers
  [parser hdrs]
  (persistent!
   (assoc!
    hdrs
    :request-method (.. parser getMethod toString)
    :path-info      (.. parser getPathInfo)
    :script-name    BLANK
    :query-string   (.. parser getQueryString)
    :http-version   [(.getHttpMajor parser) (.getHttpMinor parser)])))

(defn- mk-callback
  [f]
  (reify HttpParserCallback
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

    (^void message [_ ^HttpParser parser ^Object hdrs ^ByteBuffer body]
      (let [hdrs (request-headers parser hdrs)
            body (cond
                  body
                  body

                  (.hasBody parser)
                  :chunked

                  (.isUpgrade parser)
                  :upgraded)]
        (f :request [hdrs body])))

    (^void body [_ ^HttpParser parser ^ByteBuffer buf]
      (f :body buf))

    (^void message [_ ^HttpParser parser ^ByteBuffer buf]
      (f :message buf))))

(defn parser
  [f]
  (let [parser (HttpParser. (mk-callback f))]
    (fn [buf] (.execute parser (to-byte-buffer buf)))))
