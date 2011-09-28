(ns picard.cookie
  (:use
   [picard.helpers])
  (:require
   [clojure.contrib.logging :as log])
  (:import
   [org.jboss.netty.handler.codec.http
    Cookie
    CookieDecoder
    CookieEncoder
    DefaultCookie]))

(defn default-cookie->cookie
  [default-cookie]
  {:comment (.getComment default-cookie)
   :comment-url (.getCommentUrl default-cookie)
   :domain (.getDomain default-cookie)
   :max-age (.getMaxAge default-cookie)
   :name (.getName default-cookie)
   :path (.getPath default-cookie)
   :ports (into #{} (map #(.intValue %) (seq (.getPorts default-cookie))))
   :value (.getValue default-cookie)
   :version (.getVersion default-cookie)
   :discard? (.isDiscard default-cookie)
   :http-only? (.isHttpOnly default-cookie)
   :secure? (.isSecure default-cookie)})

(defn cookie->default-cookie
  [cookie]
  (let [{comment     :comment
         comment-url :comment-url
         domain      :domain
         max-age     :max-age
         name        :name
         path        :path
         ports       :ports
         value       :value
         version     :version
         discard?    :discard?
         http-only?  :http-only?
         secure?     :secure?} cookie
         default-cookie (DefaultCookie. name value)]
    (when-not (nil? comment) (.setComment default-cookie comment))
    (when-not (nil? comment-url) (.setCommentUrl default-cookie comment-url))
    (when-not (nil? domain) (.setDomain default-cookie domain))
    (when-not (nil? max-age) (.setMaxAge default-cookie max-age))
    (when-not (nil? path) (.setPath default-cookie path))
    (when-not (nil? ports) (.setPorts default-cookie ports))
    (when-not (nil? version) (.setVersion default-cookie version))
    (when-not (nil? discard?) (.setDiscard default-cookie discard?))
    (when-not (nil? http-only?) (.setHttpOnly default-cookie http-only?))
    (when-not (nil? secure?) (.setSecure default-cookie secure?))
    default-cookie))

(defn decode-cookies
  [hdrs hdr-name]
  (if-not (hdrs hdr-name)
    hdrs
    (try
      (let [decoder (CookieDecoder.)
            cookie-header (hdrs hdr-name)
            cookies (into #{} (map default-cookie->cookie
                                   (if (vector? cookie-header)
                                     (mapcat #(seq (.decode decoder %)) cookie-header)
                                     (seq (.decode decoder cookie-header)))))]

        (-> hdrs
            (dissoc hdr-name)
            (assoc :cookies cookies)))
      (catch Exception e
        (log/warn "bad cookie header" e)
        hdrs))))

(defn- encode-cookie
  [cookie server?]
  (let [encoder (CookieEncoder. server?)]
    (.addCookie encoder (cookie->default-cookie cookie))
    (.encode encoder)))

(defn encode-cookies
  [hdrs hdr-name server?]
  (if-not (:cookies hdrs)
    hdrs
    (try
      (let [cookie-hdrs (vec (map #(encode-cookie % server?) (:cookies hdrs)))]
        (-> hdrs
            (dissoc :cookies)
            (assoc hdr-name cookie-hdrs)))
      (catch Exception e
        (.printStackTrace e)
        (log/warn "bad cookie" e)
        hdrs))))
