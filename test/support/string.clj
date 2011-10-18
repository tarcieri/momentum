(ns support.string)

(defn- remove-el
  [el v]
  (let [idx (.indexOf v el)]
    (concat
     (subvec v 0 idx)
     (subvec v (inc idx) (count v)))))

(defn substrings?
  [coll str]
  (let [coll (vec coll)]
    (cond
     (and (empty? coll) (= "" str))
     true

     (and (seq coll) (not= "" str))
     (some
      (fn [substr]
        (and
         ;; First check that the current substring matches the front of
         ;; the string
         (= substr (subs str 0 (count substr)))
         ;; Then recursively ensure that the rest of the string matches
         ;; the rest of the collection
         (substrings?
          (remove-el substr coll)
          (subs str (count substr)))))
      coll))))
