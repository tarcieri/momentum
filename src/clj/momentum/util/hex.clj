(ns momentum.util.hex
  (:use momentum.core)
  (:import
   [momentum.util
    Hex]))

(defn encode
  [o]
  (Hex/hexEncode (buffer o)))

(defn decode
  [o]
  (Hex/hexDecode (buffer o)))
