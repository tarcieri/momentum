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
    (if ((.skippable this) evt)
      ;; Skip the gate
      (.invoke ^clojure.lang.IFn @(.upstream this) evt val)

      ;; If the gate is open, forward the message, otherwise buffer
      ;; it.
      (let [state (.state this)]
        (loop [cs @(.state this)]
          (if-let [buffer (.buffer cs)]
            (let [ns (State. (.status cs) (conj (.buffer cs) [evt val]))]
              (when-not (compare-and-set! state cs ns)
                (recur @state)))
            (.invoke ^clojure.lang.IFn @(.upstream this) evt val)))))))

(defn open!
  "Opens the gate. Buffered events will be sent upstream one at a time
  as long as the gate remains open."
  [^Gate gate]
  (let [upstream @(.upstream gate)
        state    (.state gate)]
    (loop [cs @state]
      (when (= :closed (.status cs))
        ;; First get a lock on the opening process. This prevents any
        ;; other parellel threads from concurrently opening the same
        ;; gate.
        (let [new-cs (State. :opening (.buffer cs))]
          (if (compare-and-set! state cs new-cs)
            ;; The lock as been acquired, so atomically pop off
            ;; messages and send them upstream. At each step, the gate
            ;; must be verified as still open since each message could
            ;; cause the gate to close.
            (loop [cs new-cs]
              ;; Ensure the gate is still open.
              (if (= :opening (.status cs))
                ;; When there are no more remaining messages in the
                ;; buffer to send upstream, attempt to nullify the
                ;; queue. This indicates that it is safe for any
                ;; further received messages to be sent upstream
                ;; directly instead of buffering them.
                (if (seq (.buffer cs))
                  (let [new-cs (State. :opening (pop (.buffer cs)))]
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
                (when-not (compare-and-set! state cs (State. :closed (.buffer cs)))
                  (recur @state))))
            ;; Retry establishing the open lock.
            (recur @state)))))))

(defn close!
  "Closes the gate. Any received events will be buffered."
  [gate]
  (swap!
   (.state gate)
   (fn [cs]
     (cond
      (= :open (.status cs))
      (State. :closed empty-queue)

      (= :opening (.status cs))
      (State. :closing (.buffer cs))

      :else
      cs))))

(defn- init*
  [upstream status queue]
  (Gate. (atom upstream)
         (atom (State. status queue))
         ;; Hardcode skippable events for now
         #{:pause :resume :close :abort :done}))

(defn init
  ([]         (init*      nil :open nil))
  ([upstream] (init* upstream :open nil)))

(defn init-closed
  ([]         (init*      nil :closed empty-queue))
  ([upstream] (init* upstream :closed empty-queue)))

(defn set-upstream!
  [gate f] (reset! (.upstream gate) f) gate)
