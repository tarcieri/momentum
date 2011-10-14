(ns picard.http.parser
  (:use
   picard.core.buffer)
  (:import
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

    (message [_ parser hdrs body]
      (let [hdrs (request-headers parser hdrs)
            body (cond
                  body
                  body

                  (.hasBody parser)
                  :chunked

                  (.isUpgrade parser)
                  :upgraded)]
        (f :request [hdrs body])))

    (body [_ parser buf]
      (f :body buf))

    (message [_ parser buf]
      (f :message buf))))

(defn parser
  [f]
  (let [parser (HttpParser. (mk-callback f))]
    (fn [buf] (.execute parser buf))))
