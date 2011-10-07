(ns picard.test.utils.hex
 (:use
  clojure.test
  picard.utils.buffer
  picard.utils.hex))

(deftest zomg
  (println (to-string (encode "HELLO"))))
