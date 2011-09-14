(ns picard.core.pipeline
  (:use picard.core.deferred))

(defrecord Pipeline [first last]
  DeferredValue
  (receive [pipeline callback]
    (receive (.last pipeline) callback)
    pipeline)
  (rescue [pipeline klass callback]
    (rescue (.last pipeline) klass callback)
    pipeline)
  (finalize [pipeline callback]
    (finalize (.last pipeline) callback)
    pipeline)
  (catch-all [pipeline callback]
    (catch-all (.last pipeline) callback)
    pipeline)

  DeferredRealizer
  (put [pipeline val]
    (put (.first pipeline) val)
    pipeline)
  (abort [pipeline err]
    (abort (.first pipeline) err)
    pipeline))

(defn- build-stage
  [last prev stage]
  (receive
   (deferred)
   (fn [val]
     (try
       (-> (stage val)
           (receive #(put prev %))
           (catch-all #(abort last %)))
       (catch Exception err
         (abort last err))))))

(defn build-pipeline
  [& stages]
  (let [last (deferred)]
    (Pipeline.
     (-> (reduce
          #(build-stage last %1 %2)
          last stages)
         (catch-all #(abort last %)))
     last)))

(defn- catch?
  [clause]
  (and (seq? clause) (= 'catch (first clause))))

(defn- finally?
  [clause]
  (and (seq? clause) (= 'finally (first clause))))

(defn- partition-clauses
  [clauses]
  (reduce
   (fn [[stages catches finally] clause]
     (cond
      (and (catch? clause) (not finally))
      [stages (conj catches clause) finally]

      (and (finally? clause) (not finally))
      [stages catches clause]

      (or (catch? clause) (finally? clause) (first catches) finally)
      (throw (IllegalArgumentException. (str "malformed pipeline statement: " clause)))

      :else
      [(conj stages clause) catches finally]))
   [[] [] nil] clauses))

(defn- catch-to-callback
  [[_ klass binding & stmts]]
  `(rescue ~klass (fn [~binding] ~@stmts)))

(defn- finally-to-callback
  [[_ & stmts :as clause]]
  (if clause
    [`(finalize (fn [] ~@stmts))]
    []))

(defmacro pipeline
  [seed & clauses]
  (let [[stages catches finally] (partition-clauses clauses)]
    `(-> (build-pipeline ~@stages)
         ~@(map catch-to-callback catches)
         ~@(finally-to-callback finally)
         (put ~seed))))
