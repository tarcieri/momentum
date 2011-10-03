(ns picard.test.http.multipart
  (:require
   [picard.http.multipart :as multipart])
  (:use
   clojure.test
   support.parsing))

(defn- concating-cb
  [atom]
  (fn [evt val]
    (swap! atom #(concat % [evt val]))))

(defn- parse
  [str]
  (let [retval (atom [])
        parser (multipart/parser (concating-cb retval) "zomg")]
    (parser str)
    @retval))

(deftest simple-multipart-bodies
  (is (parsed
       (str "\r\n\r\n"
            "--zomg\r\n\r\n"
            "HELLO\r\n"
            "--zomg--\r\n")
       :part [{} "HELLO"]
       :part nil))

  (is (parsed
       (str "\r\n\r\n"
            "--zomg\r\n\r\n"
            "HELLO\r\n"
            "--zomg\r\n\r\n"
            "WORLD\r\n"
            "--zomg--\r\n")
       :part [{} "HELLO"]
       :part [{} "WORLD"]
       :part nil)))

(deftest funky-delimiters
  (is (parsed
       (str "\r\n\r\n!\r\n\r\n"   ;; 8
            "--zomg\r\n\r\n"      ;; 18
            "HELLO\r\n--zo\r\n"   ;; 31
            "--zomg--\r\n")
       :part [{} "HELLO\r\n--zo"]
       :part nil))

  (with-parser #(multipart/parser % "fo:o")
    (fn []
      (is (parsed
           (str "\r\n\r\n"
                "--fo:o\r\n\r\n"
                "HELLO\r\n"
                "--fo:o--\r\n")
           :part [{} "HELLO"]
           :part nil)))))

(use-fixtures :each (fn [f] (with-parser #(multipart/parser % "zomg") f)))
