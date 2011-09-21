(ns picard.test.utils.base64
  (:use
   clojure.test
   picard.utils.base64)
  (:import
   java.nio.ByteBuffer))

(defn- str->buf
  [str]
  (ByteBuffer/wrap (.getBytes str)))

(deftest encoding-decoding-nil-returns-nil
  (is (nil? (encode nil)))
  (is (nil? (decode nil))))

(deftest encoding-decoding-empty-string-returns-empty-string
  (is (= "" (encode "")))
  (is (= (str->buf "") (decode ""))))

(deftest encoding-strings
  (is (= "Wk9NRw==" (encode "ZOMG"))))

(deftest decoding-strings
  (is (= (str->buf "ZOMG") (decode "Wk9NRw=="))))
