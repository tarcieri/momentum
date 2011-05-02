(ns picard.test.client
  (:use
   [clojure.test]
   [test-helper])
  (:require
   [picard.client :as client]))

(deftest truth
  (is (= 1 1)))
