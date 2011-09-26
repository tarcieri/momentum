(ns picard.http.parser
  (:import
   [java.nio
    ByteBuffer]
   [picard.http
    HttpParser
    HttpParserCallback]))

(defn- request-headers
  [parser hdrs]
  (persistent!
   (assoc!
    hdrs
    :request-method (.. parser getMethod toString)
    :path-info      (.. parser getPathInfo)
    :query-string   (.. parser getQueryString)
    :http-version   [(.getHttpMajor parser) (.getHttpMinor parser)])))

(defn parse
  [^HttpParser parser raw]
  (.execute parser raw))

(defn parser
  [f]
  (HttpParser.
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
             body (or body (when (.hasBody parser) :chunked))]
         (f :request [hdrs body])))

     (^void body [_ ^HttpParser parser ^ByteBuffer buf]
       (f :body buf))

     (^void message [_ ^HttpParser parser ^ByteBuffer buf]
       (f :message buf)))))
