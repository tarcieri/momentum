(ns picard.http.parser
  (:import
   [picard.http
    HttpParser
    HttpParserCallback]))

(defn- request-headers
  [parser hdrs]
  (assoc hdrs
    :request-method (.. parser getMethod toString)
    :path-info      "/"
    :http-version   [(.getHttpMajor parser) (.getHttpMinor parser)]))

(defn parse
  [^HttpParser parser raw]
  (.execute parser raw))

(defn parser
  [f]
  (HttpParser.
   (reify HttpParserCallback
     (^void request [_ ^HttpParser parser]
       (f :request [(request-headers parser {})])))))
