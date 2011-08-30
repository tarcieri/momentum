(ns picard.net.message)

(defprotocol NormalizeMessage
  (normalize [msg]))
