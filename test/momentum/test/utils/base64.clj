(ns momentum.test.utils.base64
  (:use
   clojure.test
   momentum.core.buffer
   momentum.utils.base64)
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
  (is (= (buffer "") (encode "")))
  (is (= (buffer "Wk9NRw==") (encode "ZOMG"))))

(deftest decoding-strings
  (is (= (buffer "") (decode "")))
  (is (= (buffer "ZOMG") (decode "Wk9NRw=="))))
