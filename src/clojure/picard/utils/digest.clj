(ns picard.utils.digest
  (:import
   [java.nio
    ByteBuffer]
   [java.security
    MessageDigest]))

(defprotocol Digest
  (update-digest [val digest]))

(extend-protocol Digest
  (class (byte-array 0))
  (update-digest [arr digest]
    (.update digest arr))

  String
  (update-digest [str digest]
    (update-digest (.getBytes str) digest)))

(defn update
  [digest o]
  (update-digest o digest))

(defn finish
  [digest]
  (.digest digest))

(defn sha1
  ([] (MessageDigest/getInstance "SHA1"))
  ([o]
     (let [digest (sha1)]
       (update digest o)
       (finish digest))))
