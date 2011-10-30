(ns picard.http.router
  (:use
   picard.core))

(def SEGMENT     #"[^/]+")
(def CAPTURE     #"([^/]+)")
(def GLOB        #"(.*?)")
(def PLACEHOLDER #"^(:|\*)([a-z0-9*+!-+?]+)$")

(defn- not-found
  "Basic endpoint that responds with a 404 not found"
  [dn]
  (fn [evt val]
    (when (= :request evt)
      (dn :response [404 {"content-length" "9"} (buffer "Not found")]))))

(defprotocol Dispatchable
  (^{:private true} dispatch [this state dn method path hdrs body]))

(extend-type Object
  Dispatchable
  (dispatch [this state dn method path hdrs body]
    (let [upstream (this dn)]
      (reset! state upstream)
      (upstream :request [hdrs body])
      upstream)))

(defn- re-match
  [re s]
  (when-let [match (re-find re s)]
    (if (string? match)
      (list match)
      match)))

(defrecord Route [method pattern segments names target]
  Dispatchable
  (dispatch [this state dn method path hdrs body]
    (if-let [pattern (.pattern this)]
      (when (or (nil? (.method this)) (= method (.method this)))
        (when-let [[matched & vals] (re-match pattern path)]
          (let [path (subs path (count matched))
                hdrs (assoc hdrs
                       :params      (zipmap (.names this) vals)
                       :path-info   path
                       :script-name matched)]
            (dispatch (.target this) state dn method path hdrs body))))
      (throw (Exception. "Route is not finalized")))))

(defn- compile-pattern
  [segments]
  (loop [[segment & more] segments compiled "^"]
    (if segment
      (recur more (str compiled "/+" segment))
      (re-pattern (str compiled "/*$")))))

(defn- finalize
  [route]
  (cond
   (instance? Route route)
   (assoc route :pattern (compile-pattern (.segments route)))

   (coll? route)
   (map finalize (flatten route))

   :else
   route))

(defn- parse
  [base path]
  (reduce
   (fn [{:keys [segments names] :as base} segment]
     (conj
      base
      (if-let [[_ type name :as match] (re-find PLACEHOLDER segment)]
        {:segments (conj segments (if (= ":" type) CAPTURE GLOB))
         :names    (conj names (keyword name))}
        {:segments (conj segments segment) :names names})))
   base (re-seq SEGMENT path)))

(defn match
  ([path target] (match nil path target))
  ([method path target]
     (map->Route
      (parse
       {:method method :segments [] :names [] :target target}
       path))))

(defn- handle-request
  [state routes dn [{path :path-info method :request-method :as hdrs} body]]
  (loop [[r & more] routes]
    (when-not (dispatch r state dn method path hdrs body)
      (if more
        (recur more)))))

;; Probably should add a cascade feature
(defn routing
  [& routes]
  (let [routes (finalize (conj (vec routes) not-found))]
    (fn [dn]
      (let [state (atom nil)]
        (fn [evt val]
          (when (= :abort evt)
            (.printStackTrace val))
          (if (= :request evt)
            (handle-request state routes dn val)
            (if-let [upstream @state]
              (upstream evt val)
              (throw (Exception. "Invalid state")))))))))
