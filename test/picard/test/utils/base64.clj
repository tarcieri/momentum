(ns picard.test.utils.base64
  (:use
   clojure.test
   picard.utils.base64)
  (:import
   java.nio.ByteBuffer
   java.util.Arrays))

(defn- byte=
  [a b]
  (Arrays/equals a b))

(defn- str->arr
  [str]
  (.getBytes str))

(defn- str->buf
  [str]
  (ByteBuffer/wrap (str->arr str)))

(deftest encoding-decoding-nil-returns-nil
  (is (nil? (encode nil)))
  (is (nil? (decode nil))))

(deftest encoding-strings
  (is (= "" (encode "")))
  (is (= "Wk9NRw==" (encode "ZOMG"))))

(deftest decoding-strings
  (is (byte= (str->arr "")
             (decode "")))
  (is (byte= (str->arr "ZOMG")
             (decode "Wk9NRw=="))))
