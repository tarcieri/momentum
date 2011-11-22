(ns momentum.core.channel
  (:use
   momentum.core.deferred)
  (:import
   [momentum.async
    AsyncTransferQueue]))

(declare
 channel-seq
 toggle-availability)

(deftype Channel [transfer head paused? depth f capacity]
  clojure.lang.Seqable
  (seq [_]
    @head)

  DeferredRealizer
  (put [this val]
    (let [ret (.put (.transfer this) val)]
      (when (.f this)
        (toggle-availability this))
      ret))

  (abort [this err]
    (.abort (.transfer this) err))

  clojure.lang.IFn
  (invoke [this v]
    (put this v))

  clojure.lang.Counted
  (count [this]
    (.count (.transfer this))))

(defn- full?
  [ch]
  (>= (count ch) (.capacity ch)))

(defn- toggle-availability
  [ch]
  ;; Atomically increment the depth counter
  (let [depth (swap! (.depth ch) inc)]
    ;; If the current value (after incrementing) is 1, then this
    ;; thread won the race for invoking the fn w/ :pause / :resume
    ;; events. Any losing thread has been tracked by incrementing the
    ;; counter.
    (when (= 1 depth)
      (let [f (.f ch)]
        (loop [paused? @(.paused? ch) depth depth]
          ;; If the current state of the stream does not match that of
          ;; the channel, then an event must be sent downstream
          (let [new-paused? (full? ch)]
            (when-not (= paused? new-paused?)
              (f (if new-paused? :pause :resume) nil))

            ;; Now, the counter can be decremented by the value read
            ;; after the atomic increment since all threads that
            ;; incremented the counter before the swap has been acounted
            ;; for. If the value after the decrement is not 0, then
            ;; other threads have been tracked during the downstream
            ;; function invocation, so the process must be restarted.
            (let [depth (swap! (.depth ch) #(- % depth))]
              (when (< 0 depth)
                (recur new-paused? depth)))))))))

(defn- channel-seq
  [ch]
  (async-seq
    (fn []
      (doasync (.take (.transfer ch))
        (fn [v]
          (when-not (= ::close-channel v)
            (when (.f ch)
              (toggle-availability ch))
            (let [nxt (channel-seq ch)]
              (reset! (.head ch) nxt)
              (cons v nxt))))))))

(defn channel
  ([]  (channel nil 0))
  ([f] (channel f 1))
  ([f capacity]
     (let [qu (AsyncTransferQueue. ::close-channel)
           ch (Channel. qu (atom nil) (atom false) (atom 0) f capacity)]
       (reset! (.head ch) (channel-seq ch))
       ch)))

(defn enqueue
  ([_])
  ([ch & vs]
     (loop [vs vs cont? true]
       (when-let [[v & more] vs]
         (when (and vs cont?)
           (recur more (put ch v)))))))

(defn close
  [ch]
  (.close (.transfer ch)))

(defn- sink-seq
  [coll evts]
  (async-seq
    (fn []
      (doasync (first* (select {:coll coll :evts evts}))
        (fn [[k v]]
          (when v
            (cond
             (= :coll k)
             (cons (first v) (sink-seq (next v) evts))

             (= :pause (first v))
             (doasync (next v)
               (fn [[evt & more]]
                 (if (= :pause evt)
                   (recur* more)
                   (sink-seq coll more))))

             :else
             (sink-seq coll (next v)))))))))

;; TODO: Don't hardcode this to :body events
(defn sink
  "Writes the contents of the collection to a downstream
  function. Returns a function that accepts :pause, :resume,
  and :abort events"
  [dn coll]
  (let [ch (channel)]
    (doasync (sink-seq coll (seq ch))
      (fn [coll]
        (if-let [[el & more] coll]
          (do
            (dn :body el)
            (recur* more))
          (dn :body nil)))
      ;; Handle exceptions by sending them downstream
      (catch Exception e
        (dn :abort e)))

    ;; Return an upstream function that allows sending pause / resume
    ;; events to the sink
    (fn [evt val]
      (when-not (#{:pause :resume :abort} evt)
        (throw (IllegalArgumentException. (format "Invalid events: %s" evt))))
      (if (= :abort evt)
        (abort ch val)
        (put ch evt)))))
