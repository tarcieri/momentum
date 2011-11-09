(ns momentum.utils.random
  (:use
   momentum.core.buffer)
  (:import
   [java.security
    SecureRandom]))

(defn secure-random
  [bytes]
  (let [array (byte-array bytes)]
    (.nextBytes (SecureRandom.) array)
    (buffer array)))
