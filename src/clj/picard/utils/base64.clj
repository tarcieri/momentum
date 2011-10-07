(ns picard.utils.base64
  (:import
   [java.nio
    ByteBuffer]
   [picard.utils
    Base64]))

(defprotocol IBase64
  (^{:private true} encode64 [o chunked?])
  (^{:private true} decode64 [o]))

(extend-protocol IBase64
  (class (byte-array 0))
  (encode64 [o chunked?]
    (String. (Base64/encodeBase64 o chunked?) "UTF-8"))
  (decode64 [o]
    (Base64/decodeBase64 o))

  nil
  (encode64 [_ _] nil)
  (decode64 [_] nil)

  String
  (encode64 [o chunked?]
    (encode64 (.getBytes o) chunked?))
  (decode64 [o]
    (Base64/decodeBase64 o)))

(defn encode
  ([o] (encode64 o false))
  ([o chunked?] (encode64 o chunked?)))

(def decode decode64)
