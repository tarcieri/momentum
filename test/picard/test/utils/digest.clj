(ns picard.test.utils.digest
  (:use
   clojure.test
   picard.utils.digest)
  (:require
   [picard.utils.base64 :as base64])
  (:import
   java.util.Arrays))

(defn- byte= [a b] (Arrays/equals a b))
(defn- str->arr [s] (.getBytes s))

(deftest digesting-sha1
  (is (byte= (base64/decode "2jmj7l5rSw0yVb/vlWAYkK/YBwk=") (sha1 "")))
  (is (byte= (base64/decode "VK9szMZ10Is9cyHmIIJlIHHULM8=") (sha1 "ZOMG!")))
  (is (byte= (base64/decode "VK9szMZ10Is9cyHmIIJlIHHULM8=") (sha1 (str->arr "ZOMG!")))))
