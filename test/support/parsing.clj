(ns support.parsing
  (:use
   clojure.test
   support.assertions
   momentum.core.buffer)
  (:require
   [momentum.http.parser :as http])
  (:import
   [momentum.http
    HttpParser]))

(def valid-methods
  ["HEAD"
   "GET"
   "POST"
   "PUT"
   "DELETE"
   "CONNECT"
   "OPTIONS"
   "TRACE"
   "COPY"
   "LOCK"
   "MKCOL"
   "MOVE"
   "PROPFIND"
   "PROPPATCH"
   "UNLOCK"
   "REPORT"
   "MKACTIVITY"
   "CHECKOUT"
   "MERGE"
   "MSEARCH"
   "NOTIFY"
   "SUBSCRIBE"
   "UNSUBSCRIBE"
   "PATCH"])

(def standard-headers
  #{HttpParser/HDR_ACCEPT
    HttpParser/HDR_ACCEPT_CHARSET
    HttpParser/HDR_ACCEPT_ENCODING
    HttpParser/HDR_ACCEPT_LANGUAGE
    HttpParser/HDR_ACCEPT_RANGES
    HttpParser/HDR_AGE
    HttpParser/HDR_ALLOW
    HttpParser/HDR_AUTHORIZATION
    HttpParser/HDR_CACHE_CONTROL
    HttpParser/HDR_CONNECTION
    HttpParser/HDR_CONTENT_ENCODING
    HttpParser/HDR_CONTENT_LANGUAGE
    HttpParser/HDR_CONTENT_LENGTH
    HttpParser/HDR_CONTENT_LOCATION
    HttpParser/HDR_CONTENT_MD5
    HttpParser/HDR_CONTENT_DISPOSITION
    HttpParser/HDR_CONTENT_RANGE
    HttpParser/HDR_CONTENT_TYPE
    HttpParser/HDR_COOKIE
    HttpParser/HDR_DATE
    HttpParser/HDR_DNT
    HttpParser/HDR_ETAG
    HttpParser/HDR_EXPECT
    HttpParser/HDR_EXPIRES
    HttpParser/HDR_FROM
    HttpParser/HDR_HOST
    HttpParser/HDR_IF_MATCH
    HttpParser/HDR_IF_MODIFIED_SINCE
    HttpParser/HDR_IF_NONE_MATCH
    HttpParser/HDR_IF_RANGE
    HttpParser/HDR_IF_UNMODIFIED_SINCE
    HttpParser/HDR_KEEP_ALIVE
    HttpParser/HDR_LAST_MODIFIED
    HttpParser/HDR_LINK
    HttpParser/HDR_LOCATION
    HttpParser/HDR_MAX_FORWARDS
    HttpParser/HDR_P3P
    HttpParser/HDR_PRAGMA
    HttpParser/HDR_PROXY_AUTHENTICATE
    HttpParser/HDR_PROXY_AUTHORIZATION
    HttpParser/HDR_RANGE
    HttpParser/HDR_REFERER
    HttpParser/HDR_REFRESH
    HttpParser/HDR_RETRY_AFTER
    HttpParser/HDR_SERVER
    HttpParser/HDR_SET_COOKIE
    HttpParser/HDR_STRICT_TRANSPORT_SECURITY
    HttpParser/HDR_TE
    HttpParser/HDR_TRAILER
    HttpParser/HDR_TRANSFER_ENCODING
    HttpParser/HDR_UPGRADE
    HttpParser/HDR_USER_AGENT
    HttpParser/HDR_VARY
    HttpParser/HDR_VIA
    HttpParser/HDR_WARNING
    HttpParser/HDR_WWW_AUTHENTICATE
    HttpParser/HDR_X_CONTENT_TYPE_OPTIONS
    HttpParser/HDR_X_DO_NOT_TRACK
    HttpParser/HDR_X_FORWARDED_FOR
    HttpParser/HDR_X_FORWARDED_PROTO
    HttpParser/HDR_X_FRAME_OPTIONS
    HttpParser/HDR_X_POWERED_BY
    HttpParser/HDR_X_REQUESTED_WITH
    HttpParser/HDR_X_XSS_PROTECTION})

(declare ^:dynamic *default-parser*)

(defn with-parser
  [p f]
  (binding [*default-parser* p] (f)))

(defn- normalizing
  [f]
  (fn [evt val]
    (f evt (normalize val))))

(defn- mk-callback
  [msgs]
  (fn [evt val] (swap! msgs #(conj % [evt val]))))

(defn- parsing*
  [raw f]
  (let [p (*default-parser* (normalizing f))]
    (if (coll? raw)
      (doseq [raw raw] (p (buffer raw)))
      (p (buffer raw)))))

(defn parsing
  [raw]
  (let [msgs (atom [])]
    (parsing* raw (mk-callback msgs))
    @msgs))

(defmethod assert-expr 'parsed [msg form]
  (let [[_ raw & expected] form]
    (let [expected (vec (map vec (partition 2 expected)))]
      `(let [actual#   (parsing ~raw)
             expected# ~expected]
         (do-report
          {:type (if (= expected# actual#) :pass :fail)
           :message ~msg
           :expected expected#
           :actual   actual#})))))
