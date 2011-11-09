(ns momentum.utils.hex
  (:use
   momentum.core.buffer)
  (:import
   [momentum.utils
    Hex]))

(defn encode
  [o]
  (Hex/hexEncode (buffer o)))

(defn decode
  [o]
  (Hex/hexDecode (buffer o)))
