(ns picard.utils.random
  (:use
   picard.utils.buffer)
  (:import
   [java.security
    SecureRandom]))

(defn secure-random
  [bytes]
  (let [array (byte-array bytes)]
    (.nextBytes (SecureRandom.) array)
    (buffer array)))
