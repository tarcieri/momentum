(ns picard.proxy
  (:use
   [picard.helpers]
   [picard.utils :rename {debug debug*}])
  (:require
   [clojure.string :as str]
   [clojure.contrib.string]
   [picard.client  :as client])
  (:import
   org.jboss.netty.handler.timeout.TimeoutException
   [java.net
    ConnectException]))

(defmacro debug
  [& msgs]
  `(debug* :proxy ~@msgs))

(defn- addr-from-req
  [[{proxy-host :proxy-host host "host" :as hdrs}]]
  (if proxy-host
    proxy-host
    (let [[host ^String port] (-> host str/trim (str/split #":" 2))
          ssl? (request-ssl? hdrs)]
      (if (or (nil? port) (= "" port))
        [host (if ssl? 443 80) ssl?]
        [host (try (Integer. port)
                   (catch NumberFormatException _)) ssl?]))))

(defn- chunked?
  [[_ body]]
  (= body :chunked))

(defn- bad-gateway?
  [{responded? :responded?} val]
  (and (instance? ConnectException val)
       (not responded?)))

(defn- service-unavailable?
  [{responded? :responded?} val]
  (and (instance? picard.exceptions.PoolFullException val)
       (not responded?)))

(defn- gateway-timeout?
  [{responded? :responded?} val]
  (let [ret (and (instance? TimeoutException val)
                 (not responded?))]
    ret))

(defn- add-xff-header
  [hdrs]
  (let [remote-ip (first (:remote-addr hdrs))]
    (assoc hdrs
      "x-forwarded-for"
      (if-let [current-xff (hdrs "x-forwarded-for")]
        (str current-xff ", " remote-ip)
        remote-ip))))

(defn- set-scheme
  [hdrs opts]
  (if-let [scheme (opts :scheme)]
    (assoc hdrs :picard.url-scheme (opts :scheme))
    hdrs))

(defn- process-request
  [[hdrs body] opts]
  [(-> hdrs
       add-xff-header
       (set-scheme opts))
   body])

(defn- proxy-loop?
  [[{[remote-ip] :remote-addr xff-header "x-forwarded-for"}] {cycles :cycles}]
  (when xff-header
    (< cycles
       (count (filter #(= % remote-ip)
                      (clojure.contrib.string/split #"\s*,\s*" xff-header))))))

(def bad-gateway         [502 {"content-length" "0"} nil])
(def service-unavailable [503 {"content-length" "0"} nil])
(def gateway-timeout     [504 {"content-length" "0"} nil])

(defn- initiate-request
  [state opts req downstream]
  (client/request
   (addr-from-req req) req opts
   (fn [client-dn]
     (fn [evt val]
       (debug {:msg "Client event" :event [evt val]})
       (cond
        ;; connected, resume if we're paused
        (= :connected evt)
        (locking req
          (let [pending (= :pending (@state :upstream))]
            (swap! state #(assoc % :upstream client-dn))
            (when pending (downstream :resume nil))))

        ;; response, resume if we're paused
        (= :response evt)
        (do
          ;; Because, I think that there might be some crazy race
          ;; condition where the response gets sent before :connected
          ;; TODO: Make sure that this isn't the case.
          ;; TODO: the real issue here is that the client doesn't
          ;; always send :connected
          (locking req
            (let [pending (= :pending (@state :upstream))]
              (swap! state #(assoc % :upstream client-dn :responded? true))
              (when pending (downstream :resume nil))))

          ;; send the response back to the server socket
          (downstream evt val))

        ;; body, send downstream
        (= :body evt)
        (downstream evt val)

        ;; abort
        (= :abort evt)
        (do
          (swap! state #(dissoc % :upstream))
          (let [current-state @state]
            (cond
             (bad-gateway? current-state val)
             (downstream :response bad-gateway)

             (service-unavailable? current-state val)
             (downstream :response service-unavailable)

             (gateway-timeout? current-state val)
             (downstream :response gateway-timeout)

             :else
             (downstream evt val))))

        ;; done (don't send)
        (= :done evt)
        nil

        ;; all other events send downstream
        :else
        (downstream evt val)))))

  ;; when the request is chunked, note that we're pending
  ;; connection and pause the server while we wait for the client
  ;; to connect
  (when (chunked? req)
    (locking req
      (when-not (@state :upstream)
        (swap! state #(assoc % :upstream :pending))
        (downstream :pause nil)))))

(def default-options
  {:cycles 0
   :scheme nil})

(defn mk-proxy
  ([] (mk-proxy {}))
  ([opts]
     (let [opts (merge default-options opts)]
       (fn [downstream]
         (let [state (atom {})]
           (defstream

             ;; Handling the initial request
             (request [req]
               (debug {:msg "Receiving request" :event [:request req]})

               (if-not (proxy-loop? req opts)
                 ;; not a proxy loop, initiate the request
                 (let [req (process-request req opts)]
                   (initiate-request state opts req downstream))

                 ;; proxy loop, return bad gateway
                 (do
                   (debug {:msg   "In proxy loop" :event [:request req]})
                   (downstream :response bad-gateway))))

             ;; ignore :done events
             (done [])

             ;; all other events go upstream (client's downstream)
             (else [evt val]
               (let [upstream (@state :upstream)]
                 (if (and upstream (not= :pending upstream))
                   (upstream evt val)
                   (throw (Exception. (str "proxy is not expecting message, " [evt val]))))))))))))
