(ns momentum.http.routing
  (:use
   momentum.core))

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

(defn- slice-path
  [path m]
  (if (= path m) "" (subs path (dec (count m)))))

(defrecord Route [method pattern segments names target anchor?]
  Dispatchable
  (dispatch [this state dn method path hdrs body]
    (if-let [pattern (.pattern this)]
      (when (or (nil? (.method this)) (= method (.method this)))
        (when-let [[m sn & vals] (re-match pattern path)]
          (let [hdrs (assoc hdrs
                       :params      (zipmap (.names this) vals)
                       :path-info   (slice-path path m)
                       :script-name sn)]
            (dispatch (.target this) state dn method path hdrs body))))
      (throw (Exception. "Route is not finalized")))))

(defn- compile-pattern
  [segments anchor?]
  (loop [[segment & more] segments compiled "^("]
    (if segment
      (recur more (str compiled "/+" segment))
      (re-pattern (str compiled ")/*" (when anchor? "$"))))))

(defn- finalize
  [route]
  (cond
   (instance? Route route)
   (let [pattern (compile-pattern (.segments route) (.anchor? route))]
     (assoc route :pattern pattern))

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
   base (reverse (re-seq SEGMENT path))))

(defn- match*
  [method path target anchor?]
  (map->Route
   (parse
    {:method method :segments (list) :names (list) :target target :anchor? anchor?}
    path)))

(defn match
  ([path target]        (match* nil path target true))
  ([method path target] (match* (name method) path target true)))

(defn scope
  [path & targets]
  (map
   (fn [target]
     (if (instance? Route target)
       (map->Route (parse target path))
       (match* nil path target false)))
   targets))

(defn- handle-request
  [state routes dn [{path :path-info method :request-method :as hdrs} body]]
  (loop [[r & more] routes]
    (when-not (dispatch r state dn method path hdrs body)
      (if more
        (recur more)))))

(defn routing
  [& routes]
  (let [routes (finalize (conj (vec routes) not-found))]
    (fn [dn]
      (let [state (atom nil)]
        (fn [evt val]
          (if (= :request evt)
            (handle-request state routes dn val)
            (if-let [upstream @state]
              (upstream evt val)
              (throw (Exception. "Invalid state")))))))))
