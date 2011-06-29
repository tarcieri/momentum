(ns picard.ssl
  (:import
   [javax.net.ssl
    SSLContext]))

(defn- mk-ssl-context
  []
  (let [context (SSLContext/getInstance "TLS")]
    (.init context nil nil nil)
    context))

(defn mk-client-ssl-engine
  []
  (let [context (mk-ssl-context)
        engine (.createSSLEngine context)]
    (.setUseClientMode engine true)
    engine))
