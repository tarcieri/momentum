(ns momentum.util.random
  (:use momentum.core)
  (:import
   [java.security
    SecureRandom]))

(defn secure-random
  [bytes]
  (let [array (byte-array bytes)]
    (.nextBytes (SecureRandom.) array)
    (buffer array)))
