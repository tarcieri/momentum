(ns picard.utils.hex
  (:use
   picard.core.buffer)
  (:import
   [picard.utils
    Hex]))

(defn encode
  [o]
  (Hex/hexEncode (buffer o)))

(defn decode
  [o]
  (Hex/hexDecode (buffer o)))
