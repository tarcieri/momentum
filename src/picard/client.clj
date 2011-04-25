(ns picard.client
  (:require
   [clojure.string :as str]
   [picard.netty :as netty]
   [picard.pool :as pool])
  (:import
   [org.jboss.netty.handler.codec.http
    DefaultHttpRequest
    HttpMethod
    HttpRequestEncoder
    HttpResponse
    HttpResponseDecoder
    HttpVersion]))

(defn- headers-to-netty-req
  [hdrs]
  (let [method (HttpMethod. (hdrs :request-method))
        path (hdrs :path-info)
        req (DefaultHttpRequest. HttpVersion/HTTP_1_1 method path)]
    (doseq [[k v] hdrs]
      (if (string? k) (.addHeader req k v)))
    req))

(defn- netty-bridge
  [dwnstream]
  (netty/message-stage
   (fn [ch resp]
     (if (instance? HttpResponse resp)
       (let [hdrs (into {} (map
                            (fn [[k v]] [(str/lower-case k) v])
                            (.getHeaders resp)))]
         (dwnstream
          :respond
          [(.. resp getStatus getCode)
           hdrs
           (.. resp getContent (toString "UTF-8"))])
         nil)
       (do
         (dwnstream :body (.. resp getContent (toString "UTF-8")))
         (when (.isLast resp)
           (dwnstream :done nil))
         nil)))))

(defn- create-pipeline
  [resp]
  (netty/create-pipeline
   :decoder (HttpResponseDecoder.)
   :encoder (HttpRequestEncoder.)
   :handler (netty-bridge resp)))

(defn mk-proxy
  []
  (fn [resp]
    (fn [evt val]
      (when (= :headers evt)
        (netty/connect-client
         (create-pipeline resp)
         (val "host")
         (fn [ch] (.write ch (headers-to-netty-req val))))))))
