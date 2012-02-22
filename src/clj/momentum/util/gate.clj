(ns ^{:author "Carl Lerche"
      :doc
      "Provides a gate wrapper for event handler functions.

       With the base network event handling API, there is no guarantee
       that the :pause event will halt messages immediately. Usually
       this is ok as the primary use case for the :pause event is to
       throttle messages. However, sometimes there are semantic
       reasons to dealy further events."}
  momentum.util.gate)

(def empty-queue clojure.lang.PersistentQueue/EMPTY)

(deftype State [status buffer])

(deftype Gate [upstream state skippable]

  clojure.lang.IFn
  (invoke [this evt val]
    (let [state (.state this)]
      (loop [cs @(.state this)]
        (cond
         (= :closed (.status cs))
         (throw (Exception. "Not expecting any further messages"))

         ((.skippable this) evt)
         (.invoke ^clojure.lang.IFn @(.upstream this) evt val)

         :else
         (if-let [buffer (.buffer cs)]
           (let [ns (State. (.status cs) (conj (.buffer cs) [evt val]))]
             (when-not (compare-and-set! state cs ns)
               (recur @state)))
           ;; Make sure the gate isn't closed
           (if (= :closed (.status cs))
             (throw (Exception. "Not expecting any further messages"))
             (.invoke ^clojure.lang.IFn @(.upstream this) evt val))))))))

(defn resume!
  "Resumes the gate. Buffered events will be sent upstream one at a time
  as long as the gate remains open."
  [^Gate gate]
  (let [upstream @(.upstream gate)
        state    (.state gate)]
    (loop [cs @state]
      (when (= :paused (.status cs))
        ;; First get a lock on the opening process. This prevents any
        ;; other parellel threads from concurrently opening the same
        ;; gate.
        (let [new-cs (State. :resuming (.buffer cs))]
          (if (compare-and-set! state cs new-cs)
            ;; The lock as been acquired, so atomically pop off
            ;; messages and send them upstream. At each step, the gate
            ;; must be verified as still open since each message could
            ;; cause the gate to close.
            (loop [cs new-cs]
              ;; Ensure the gate is still open.
              (if (= :resuming (.status cs))
                ;; When there are no more remaining messages in the
                ;; buffer to send upstream, attempt to nullify the
                ;; queue. This indicates that it is safe for any
                ;; further received messages to be sent upstream
                ;; directly instead of buffering them.
                (if (seq (.buffer cs))
                  (let [new-cs (State. :resuming (pop (.buffer cs)))]
                    ;; There are still messages to send upstream, so
                    ;; attempt to pop the first event to send up. If
                    ;; unsuccessful, something has changed, so read
                    ;; the atom again and try over. When successful,
                    ;; recur with current value of state without
                    ;; reading from the atom again (since in theory we
                    ;; have the most up to date version).
                    (if (compare-and-set! state cs new-cs)
                      (let [[evt val] (peek (.buffer cs))]
                        (upstream evt val)
                        (recur new-cs))
                      (recur @state)))
                  (when-not (compare-and-set! state cs (State. :open nil))
                    (recur @state)))
                ;; Otherwise, the gate has been marked to be closed,
                ;; so we should close it.
                (when-not (compare-and-set! state cs (State. :paused (.buffer cs)))
                  (recur @state))))
            ;; Retry establishing the open lock.
            (recur @state)))))))

(defn pause!
  "Pauses the gate. Any received events will be buffered."
  [gate]
  (swap!
   (.state gate)
   (fn [cs]
     (cond
      (= :open (.status cs))
      (State. :paused empty-queue)

      (= :resuming (.status cs))
      (State. :pausing (.buffer cs))

      :else
      cs))))

(defn close!
  "Closes the gate."
  [gate]
  (reset! (.state gate) (State. :closed nil)))

(defn- init*
  [upstream status queue]
  (Gate. (atom upstream)
         (atom (State. status queue))
         ;; Hardcode skippable events for now
         #{:pause :resume :close :abort}))

(defn init
  ([]         (init*      nil :open nil))
  ([upstream] (init* upstream :open nil)))

(defn init-paused
  ([]         (init*      nil :paused empty-queue))
  ([upstream] (init* upstream :paused empty-queue)))

(defn set-upstream!
  [gate f] (reset! (.upstream gate) f) gate)
