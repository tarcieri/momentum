(ns picard.http.core
  (:require
   [clojure.string :as str])
  (:import
   [org.jboss.netty.handler.codec.http
    DefaultHttpChunk
    DefaultHttpResponse
    HttpChunk
    HttpHeaders
    HttpMessage
    HttpMethod
    HttpRequest
    HttpResponse
    HttpResponseStatus
    HttpVersion]))

(def http-1-0 [1 0])
(def http-1-1 [1 1])

(defprotocol NormalizeRequest
  (normalize-request [req]))

(extend-protocol NormalizeRequest
  HttpRequest
  (normalize-request [req]
    (throw (Exception. "Not implemented yet")))
  HttpChunk
  (normalize-request [_]
    (throw (Exception. "Expecting HttpRequest, not HttpChunk")))
  clojure.lang.PersistentVector
  (normalize-request [req]
    req)
  Object
  (normalize-request [obj]
    (throw (Exception. (str "`" obj "` is not a valid request")))))

(defn keepalive-request?
  [[{version :http-version connection "connection"}]]
  (let [connection (and connection (str/lower-case connection))]
    (if (= http-1-1 version)
      (not= "close" connection)
      (= "keep-alive" connection))))

(defn expects-100?
  [{version :http-version expect "expect"}])
