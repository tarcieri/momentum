(ns picard.http.server
  (:use picard.http.core))

(declare
 initial-request
 initial-response)

(defrecord State
    [next-up-fn
     next-dn-fn
     keepalive?
     chunked?
     head?
     responded?])

(defn- initial-state
  []
  (State.
   initial-request
   initial-response
   true
   false
   false
   false))

(defn- handle-response
  [])

(defn- stream-or-finalize-response
  [])

(defn- mk-downstream-fn
  [next-dn state]
  (fn [evt val]
    ;; TODO: Do more than this
    (next-dn evt val)))

(defn- handle-request
  [state request current-state]
  (let [request    (normalize-request request)
        keepalive? (keepalive-request? request)]))

(defn- stream-or-finalize-request
  [])

(defn proto
  [app]
  (fn [dn]
    (let [state (atom (initial-state))]
      (fn [evt val]
        (cond
         (= :message evt)
         (let [current-state @state]
           ((.next-up-fn state val current-state)))

         :else
         (next-up evt val))))))
