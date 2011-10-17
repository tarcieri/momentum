(ns picard.test.utils.digest
  (:use
   clojure.test
   picard.core.buffer
   picard.utils.digest)
  (:require
   [picard.utils.base64 :as base64])
  (:import
   java.util.Arrays))

(defn- byte= [a b] (Arrays/equals a b))

(deftest digesting-sha1
  (is (= (base64/decode "2jmj7l5rSw0yVb/vlWAYkK/YBwk=") (sha1 "")))

  (let [expected (base64/decode "VK9szMZ10Is9cyHmIIJlIHHULM8=")]
    (is (= expected (sha1 "ZOMG!")))
    (is (= expected (sha1 (.getBytes "ZOMG!" "UTF-8"))))
    (is (= expected (sha1 (buffer "ZOMG!"))))))
