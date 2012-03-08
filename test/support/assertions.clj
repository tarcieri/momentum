(ns support.assertions
  (:use
   clojure.test
   momentum.core)
  (:import
   [java.nio
    ByteBuffer]))

(defn normalize
  [val]
  (try
    (cond
     (vector? val)
     (vec (map normalize val))

     (map? val)
     (into {} (map (comp vec normalize vec) val))

     (buffer? val)
     (to-string val)

     (instance? ByteBuffer val)
     (let [val (.duplicate val)
           arr (byte-array (.remaining val))]
       (.get val arr)
       (String. arr))

     :else
     val)
    (catch Exception e (.printStackTrace e))))

(defn- match-values
  [val val*]
  (cond
   (= val :dont-care)
   true

   (set? val)
   ((first val) val*)

   (and (map? val) (= (count val) (count val*)))
   (every? (fn [[k v]] (match-values v (val* k))) val)

   (and (vector? val) (vector? val*) (= (count val) (count val*)))
   (every? #(apply match-values %) (map vector val val*))

   :else
   (or (= val val*)
       (and (fn? val) (val val*)))))

(defn- blocking*
  [seq]
  (map normalize (blocking seq 2000 ::timeout)))

(defn assert-no-msgs
  [f msg chs]
  (Thread/sleep 50)
  (let [received (into {} (filter (fn [[_ ch]] (not= 0 (count ch))) chs))]
    (f {:type     (if (empty? received) :pass :fail)
        :message  msg
        :expected []
        :actual   received})))

(defn assert-msgs
  [f msg ch closed? & expected]
  (when (odd? (count expected))
    (throw (IllegalArgumentException. "Requires even number of messages")))

  (let [expected (partition 2 expected)
        actual
        (if closed?
          (blocking* ch)
          (take (count expected) (blocking* ch)))
        pass?
        (and
         (= (count expected) (count actual))
         (every? identity (map #(match-values (vec %1) %2) expected actual)))]

    (f {:type     (if pass? :pass :fail)
        :message  msg
        :expected expected
        :actual   actual})))

(defmethod assert-expr 'next-msgs [msg form]
  (let [[_ ch & stmts] form]
    `(assert-msgs #(do-report %) ~msg ~ch false ~@stmts)))

(defmethod assert-expr 'msg-match [msg form]
  (let [[_ ch & stmts] form]
    `(assert-msgs #(do-report %) ~msg ~ch true ~@stmts)))

(defmethod assert-expr 'no-msgs [msg form]
  (let [[_ & args] form
        chs (zipmap (map str args) args)]
    `(assert-no-msgs #(do-report %) ~msg ~chs)))
