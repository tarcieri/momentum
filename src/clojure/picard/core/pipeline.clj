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

(defn pipeline
  [seed & stages]
  (-> (apply build-pipeline stages)
      (put seed)))
