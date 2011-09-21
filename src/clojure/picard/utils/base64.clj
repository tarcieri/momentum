(ns picard.utils.base64
  (:import
   [java.nio
    ByteBuffer]
   [picard.utils
    Base64]))

(defprotocol IBase64
  (encode64 [o chunked?])
  (decode64 [o]))

(extend-protocol IBase64
  nil
  (encode64 [_ _] nil)
  (decode64 [_] nil)

  String
  (encode64 [o chunked?]
    (-> o .getBytes (Base64/encodeBase64 chunked?) (String. "UTF-8")))
  (decode64 [o]
    (-> o Base64/decodeBase64 ByteBuffer/wrap)))

(defn encode
  ([o] (encode64 o false))
  ([o chunked?] (encode64 o chunked?)))

(def decode decode64)
