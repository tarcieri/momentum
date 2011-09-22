(ns picard.test.http.parser
  (:use
   clojure.test
   picard.http.parser))

(def valid-methods
  ["HEAD"
   "GET"
   "POST"
   "PUT"
   "DELETE"
   "CONNECT"
   "OPTIONS"
   "TRACE"
   "COPY"
   "LOCK"
   "MKCOL"
   "MOVE"
   "PROPFIND"
   "PROPPATCH"
   "UNLOCK"
   "REPORT"
   "MKACTIVITY"
   "CHECKOUT"
   "MERGE"
   "MSEARCH"
   "NOTIFY"
   "SUBSCRIBE"
   "UNSUBSCRIBE"
   "PATCH"])

(defn- is-parsed-as
  [msg raw & expected]
  (let [res      (atom [])
        expected (vec (map vec (partition 2 expected)))
        p        (parser (fn [evt val] (swap! res #(conj % [evt val]))))]
    (parse p raw)
    (do-report
     {:type    (if (= expected @res) :pass :fail)
      :message  msg
      :expected expected
      :actual   @res})))

(defmethod assert-expr 'parsed-as [msg form]
  (let [[_ raw & expected] form]
    `(is-parsed-as ~msg ~raw ~@expected)))

;; ==== REQUEST LINE TESTS

(deftest parsing-normal-request-lines
  (doseq [method valid-methods]
    (is (parsed-as
         (str method " / HTTP/1.1\r\n\r\n")
         :request [{:request-method method
                    :path-info      "/"
                    :http-version   [1 1]}]))))
