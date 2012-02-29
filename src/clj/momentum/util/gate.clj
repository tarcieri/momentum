(ns ^{:author "Carl Lerche"
      :doc
      "Provides a gate wrapper for event handler functions.

       With the base network event handling API, there is no guarantee
       that the :pause event will halt messages immediately. Usually
       this is ok as the primary use case for the :pause event is to
       throttle messages. However, sometimes there are semantic
       reasons to dealy further events."}
  momentum.util.gate
  (:use momentum.core.atomic))

(def empty-queue clojure.lang.PersistentQueue/EMPTY)

(deftype State [converging? open? buffer final?])

(deftype Gate [upstream downstream state skippable]

  clojure.lang.IFn
  (invoke [this evt val]
    (let [state (.state this)]
      (loop [cs ^State @(.state this)]
        (cond
         (.final? cs)
         (throw (Exception. "Not expecting any further messages"))

         ((.skippable this) evt)
         (.invoke ^clojure.lang.IFn @(.upstream this) evt val)

         :else
         (if-let [buffer (.buffer cs)]
           (let [ns (State. (.converging? cs) (.open? cs) (conj (.buffer cs) [evt val]) false)]
             (when-not (compare-and-set! state cs ns)
               (recur @state)))
           (.invoke ^clojure.lang.IFn @(.upstream this) evt val)))))))

(defn- converge
  [state upstream downstream ^State cs open?]
  (when-not (or (= open? (.open? cs)) (.final? cs))
    (loop [cs cs]
      (if (.converging? cs)
        ;; The lock has already been acquired. Another thread is doing the
        ;; work of converging towards the appropriate state. So, just
        ;; update the end goal.
        (let [ns (State. true open? (.buffer cs) false)]
          (when-not (compare-and-set! state cs ns)
            (recur @state)))

        ;; No other thread is currently attempting to converge the state,
        ;; so attempt to get the lock.
        (let [ns (State. true open? (.buffer cs) false)]
          (if (compare-and-set! state cs ns)
            ;; The lock has been acquired. Start converging.
            (loop [cs ns upstream-open? (.open? cs)]
              (when-not (.final? cs)
                ;; Since any parallel thread that does not obtain the lock
                ;; is able to change the value of open?, each iteration,
                ;; the latest value is checked and an action is taken
                ;; based on what it currently is.
                (if (.open? cs)
                  (if (seq (.buffer cs))
                    ;; There are still messages to send upstream, so
                    ;; attempt to pop off the first event.
                    (let [ns (State. true (.open? cs) (pop (.buffer cs)) false)]
                      (if (compare-and-set! state cs ns)
                        (let [[evt val] (peek (.buffer cs))]
                          (upstream evt val)
                          (recur ns upstream-open?))
                        (recur @state upstream-open?)))
                    ;; There are no more buffered events, so start the
                    ;; process of releasing the converge lock.
                    (do
                      ;; If there is a downstream fn, send a :resume event.
                      (when (and downstream (not upstream-open?))
                        (downstream :resume nil))

                      ;; Attempt releasing the lock
                      (when-not (compare-and-set! state cs (State. false true nil false))
                        (recur @state true))))

                  ;; Handling closing the gate is much easier.
                  (do
                    ;; First send a :pause event downstream if needed
                    (when (and downstream upstream-open?)
                      (downstream :pause nil))

                    ;; Then attempt to close the gate and release the
                    ;; lock
                    (when-not (compare-and-set! state cs (State. false false (or (.buffer cs) empty-queue) false))
                      (recur @state false))))))

            ;; If the the CAS to acquire the lock failed, just recur.
            (recur @state)))))))

(defn resume!
  [^Gate gate]
  (converge
   (.state gate)
   @(.upstream gate)
   @(.downstream gate)
   @(.state gate)
   true))

(defn pause!
  [^Gate gate]
  (converge
   (.state gate)
   @(.upstream gate)
   @(.downstream gate)
   @(.state gate)
   false))

(defn close!
  "Closes the gate."
  [^Gate gate] (reset! (.state gate) (State. false false nil true)))

(defn- init*
  [upstream open? queue]
  (Gate. (atom upstream)
         (atom nil)
         (atom (State. false open? queue false))
         ;; Hardcode skippable events for now
         #{:pause :resume :close :abort}))

(defn init
  ([]         (init*      nil true nil))
  ([upstream] (init* upstream true nil)))

(defn init-paused
  ([]         (init*      nil false empty-queue))
  ([upstream] (init* upstream false empty-queue)))

(defn set-upstream!
  [^Gate gate f] (reset! (.upstream gate) f) gate)

(defn set-downstream!
  [^Gate gate f] (reset! (.downstream gate) f) gate)
