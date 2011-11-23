(ns momentum.util.digest
  (:use momentum.core)
  (:import
   [momentum.buffer
    Buffer]
   [java.nio
    ByteBuffer]
   [java.security
    MessageDigest]))

(defprotocol Digest
  (^{:private true} update-digest [val digest]))

(extend-protocol Digest
  (class (byte-array 0))
  (update-digest [arr digest]
    (.update digest arr))

  String
  (update-digest [str digest]
    (update-digest (.getBytes str) digest))

  Buffer
  (update-digest [buf digest]
    (update-digest (to-byte-array buf) digest)))

(defn update
  [digest o]
  (update-digest o digest))

(defn finish
  [digest]
  (buffer (.digest digest)))

(defn sha1
  ([] (MessageDigest/getInstance "SHA1"))
  ([o]
     (let [digest (sha1)]
       (update digest o)
       (finish digest))))
