(ns momentum.net.core
  (:use
   momentum.core)
  (:import
   [momentum.reactor
    ChannelHandler
    ReactorCluster
    Upstream
    UpstreamFactory]
   [java.net
    InetSocketAddress
    Socket]
   [java.nio.channels
    SocketChannel]))

(defn- ^InetSocketAddress to-socket-addr
  [[^String host port]]
  (let [port (int (or port 80))]
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
  [^ChannelHandler dn]
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
  [^ChannelHandler downstream upstream]
  (reify Upstream
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

(defn- ^UpstreamFactory mk-upstream-factory
  [app {host :host port :port :as opts}]
  (let [addr (to-socket-addr [host port])]
    (reify UpstreamFactory
      (getAddr [_] addr)
      (getUpstream [_ dn]
        (mk-upstream dn (app (mk-downstream dn) {}))))))

(defn start-tcp-server
  [app opts]
  (let [factory (mk-upstream-factory app opts)
        handle  (.startTcpServer ^ReactorCluster reactors factory)]
    (reify
      clojure.lang.IFn
      (invoke [_] (.close handle))

      clojure.lang.IDeref
      (deref [this]
        @(.bound handle)
        true))))

(defn connect-tcp-client
  [app opts]
  (let [factory (mk-upstream-factory app opts)]
    (.connectTcpClient ^ReactorCluster reactors factory)))