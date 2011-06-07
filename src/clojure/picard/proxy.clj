(ns picard.proxy
  (:use
   [picard.helpers]
   [picard.utils])
  (:require
   [clojure.string :as str]
   [picard.client  :as client])
  (:import
   [java.net
    ConnectException]))

(defn- addr-from-req
  [[{host "host"}]]
  (let [[host port] (-> host str/trim (str/split #":" 2))]
    (if (or (nil? port) (= "" port))
     [host nil]
     [host (try (Integer. port) (catch NumberFormatException _))])))

(defn- chunked?
  [[_ _ body]]
  (= body :chunked))

(defn- bad-gateway?
  [current-state evt val]
  (and (= :abort evt)
       (instance? ConnectException val)
       (or (nil? current-state)
           (= :pending current-state))))

(defn- add-xff-header
  [[hdrs body]]
  (let [remote-ip (first (:remote-addr hdrs))]
    [(assoc hdrs
       "x-forwarded-for"
       (if-let [current-xff (hdrs "x-forwarded-for")]
         (str current-xff ", " remote-ip)
         remote-ip)) body]))

(defn mk-proxy
  ([] (mk-proxy client/GLOBAL-POOL))
  ([pool]
     (fn [downstream]
       (let [state (atom nil)]
         (defstream
           ;; Handling the initial request
           (request [req]
             (client/request
              pool (addr-from-req req) (add-xff-header req)
              (fn [upstream evt val]
                (cond
                 (bad-gateway? @state evt val)
                 (downstream :response [502 {"content-length" "0"} nil])

                 (= :connected evt)
                 (locking req
                   (let [current-state @state]
                     (when (= :pending current-state)
                       (downstream :resume nil))
                     (reset! state upstream)))

                 :else
                 (downstream evt val))))
             (when (chunked? req)
               (locking req
                 (when-not @state
                   (reset! state :pending)
                   (downstream :pause nil)))))
           ;; Handling all other events
           (:else [evt val]
             (if-let [upstream @state]
               (upstream evt val)
               (throw (Exception. "Somehow, the upstream isn't set")))))))))
