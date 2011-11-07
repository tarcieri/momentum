(ns picard.http.parser
  (:use
   picard.core.buffer)
  (:import
   [picard.http
    HttpParser
    HttpParserCallback]))

(def BLANK "")

(defn- http-version
  [parser]
  [(.getHttpMajor parser) (.getHttpMinor parser)])

(defn- request-headers
  [hdrs parser]
  (persistent!
   (assoc!
    hdrs
    :request-method (.. parser getMethod toString)
    :path-info      (.. parser getPathInfo)
    :script-name    BLANK
    :query-string   (.. parser getQueryString)
    :http-version   (http-version parser))))

(defn- response-headers
  [hdrs parser]
  (persistent!
   (assoc! hdrs :http-version (http-version parser))))

(defn- map-body
  [body parser]
  (cond
   body                body
   (.hasBody parser)   :chunked
   (.isUpgrade parser) :upgraded))

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

    (request [_ parser hdrs body]
      (f :request [(request-headers hdrs parser) (map-body body parser)]))

    (response [_ parser status hdrs body]
      (f :response [status (response-headers hdrs parser) (map-body body parser)]))

    (body [_ parser buf]
      (f :body buf))

    (message [_ parser buf]
      (f :message buf))))

(defn request
  [f]
  (let [parser (HttpParser/request (mk-callback f))]
    (fn [buf] (.execute parser buf))))

(defn response
  [f]
  (let [parser (HttpParser/response (mk-callback f))]
    (fn [buf] (.execute parser buf))))
