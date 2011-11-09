(ns momentum.test.utils.digest
  (:use
   clojure.test
   momentum.core.buffer
   momentum.utils.digest)
  (:require
   [momentum.utils.base64 :as base64])
  (:import
   java.util.Arrays))

(defn- byte= [a b] (Arrays/equals a b))

(deftest digesting-sha1
  (is (= (base64/decode "2jmj7l5rSw0yVb/vlWAYkK/YBwk=") (sha1 "")))

  (let [expected (base64/decode "VK9szMZ10Is9cyHmIIJlIHHULM8=")]
    (is (= expected (sha1 "ZOMG!")))
    (is (= expected (sha1 (.getBytes "ZOMG!" "UTF-8"))))
    (is (= expected (sha1 (buffer "ZOMG!"))))))
