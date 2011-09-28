(ns picard.ssl
  (:import
   [org.jboss.netty.handler.ssl
    SslHandler]
   [javax.net.ssl
    SSLContext
    TrustManager]))

(defn- mk-ssl-context
  []
  (let [context (SSLContext/getInstance "TLS")]
    (.init context nil nil nil)
    context))

(def default-context (mk-ssl-context))

(defn mk-client-ssl-engine
  []
  (let [engine  (.createSSLEngine default-context)]
    (.setUseClientMode engine true)
    engine))

(defn mk-client-handler [] (SslHandler. (mk-client-ssl-engine)))
