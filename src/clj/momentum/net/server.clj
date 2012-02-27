(ns momentum.net.server
  (:use
   momentum.core)
  (:import
   [momentum.net
    ReactorChannelHandler
    ReactorCluster
    ReactorServerHandler
    ReactorUpstream
    ReactorUpstreamFactory
    TCPServer]
   [java.net
    InetSocketAddress
    Socket]
   [java.nio.channels
    SocketChannel]))

(def reactor-cluster (ReactorCluster.))

(defn- ^InetSocketAddress to-socket-addr
  [[host port]]
  (let [port (or port 80)]
    (if host
      (InetSocketAddress. host port)
      (InetSocketAddress. port))))

(defn- from-socket-addr
  [^InetSocketAddress addr]
  [(.. addr getAddress getHostAddress) (.getPort addr)])

(defn- channel-info
  [^SocketChannel ch]
  (let [sock (.socket ch)]
    {:local-addr  (from-socket-addr (.getLocalSocketAddress sock))
     :remote-addr (from-socket-addr (.getRemoteSocketAddress sock))}))

(defn- mk-downstream
  [^ReactorChannelHandler dn]
  (fn [evt val]
    (cond
     (= :message evt)
     (.sendMessageDownstream dn val)

     (= :close evt)
     (.sendCloseDownstream dn)

     (= :pause evt)
     (.sendPauseDownstream dn)

     (= :resume evt)
     (.sendResumeDownstream dn)

     (= :abort evt)
     (.sendAbortDownstream dn val)

     :else
     (throw (Exception. (str "Unknown event : " evt))))))

(defn- mk-upstream
  [^ReactorChannelHandler downstream upstream]
  (reify ReactorUpstream
    (sendOpen [_ ch]
      (upstream :open (channel-info ch)))

    (sendMessage [_ buf]
      (upstream :message buf))

    (sendClose [_]
      (upstream :close nil))

    (sendPause [_]
      (upstream :pause nil))

    (sendResume [_]
      (upstream :resume nil))

    (sendAbort [_ err]
      (upstream :abort err))))

(defn- mk-tcp-server
  [app {host :host port :port :as opts}]
  (let [addr (to-socket-addr [host (or port 4040)])]
    (reify TCPServer
      (getBindAddr [_] addr)
      (getUpstream [_ dn]
        (mk-upstream dn (app (mk-downstream dn) {}))))))

(defn start
  ([app] (start app {}))
  ([app opts]
     (let [srv    (mk-tcp-server app opts)
           handle (.startTcpServer reactor-cluster srv)]
       (reify
         clojure.lang.IFn
         (invoke [_]
           (.close handle))

         clojure.lang.IDeref
         (deref [this]
           @(.bound handle)
           this)))))

(defn stop [f] (f))
