(ns picard.proxy
  (:use
   [picard.api]
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

;; (defn mk-proxy
;;   ([] (mk-proxy client/GLOBAL-POOL))
;;   ([pool]
;;      (fn [downstream req]
;;        (let [state (atom nil)]
;;         (with
;;          (client/request
;;           pool (addr-from-req req) req
;;           (fn [upstream evt val]
;;             ;; Not able to connect to the end server
;;             (when (and (= :abort evt)
;;                        (instance? ConnectException val)
;;                        (not= :connected @state))
;;               (downstream :response
;;                           [502 {"content-length" "20"}
;;                            "<h2>Bad Gateway</h2>"]))

;;             ;; Otherwise, handle stuff
;;             (if (= :connected evt)
;;               (locking req
;;                 (let [current-state @state]
;;                   (when (= :pending current-state)
;;                     (downstream :resume nil))
;;                   (hard-set! state :connected)))
;;               (downstream evt val))))
;;          :as upstream
;;          (when (chunked? req)
;;            (locking req
;;              (let [current-state @state]
;;                (when (not= :connected current-state)
;;                  (hard-set! state :pending)
;;                  (downstream :pause nil)))))
;;          (fn [evt val] (upstream evt val)))))))

(defn mk-proxy
  ([] (mk-proxy client/GLOBAL-POOL))
  ([pool]
     (fn [downstream]
       (let [state (atom nil)]
         (defupstream
           ;; Handling the initial request
           (request [req]
             (with
              (client/request
               pool (addr-from-req req) req
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
              :as upstream
              (when (chunked? req)
                (locking req
                  (when-not @state
                    (reset! state :pending)
                    (downstream :pause nil))))))
           ;; Handling all other events
           (:else [evt val]
             (if-let [upstream @state]
               (upstream evt val)
               (throw (Exception. "Somehow, the upstream isn't set")))))))))
