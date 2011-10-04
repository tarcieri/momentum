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
       :part nil))

  (is (parsed
       ["\r\n\r\n--zomg\r\n\r\n"
        "HELLO\r\n--zomg--\r\n"]
       :part [{} "HELLO"]
       :part nil)))

(deftest simple-chunked-multipart-bodies
  (is (parsed
       ["\r\n\r\n"
        "--zomg\r\n\r\nHELLO\r\n--zomg--\r\n"]
       :part [{} "HELLO"]
       :part nil))

  (is (parsed
       ["\r\n\r\n--"
        "zomg\r\n\r\nHELLO\r\n--zomg--\r\n"]
       :part [{} "HELLO"]
       :part nil))

  (is (parsed
       ["\r\n\r\n--zo"
        "mg\r\n\r\nHELLO\r\n--zomg--\r\n"]
       :part [{} "HELLO"]
       :part nil))

  (is (parsed
       ["\r\n\r\n--zomg"
        "\r\n\r\nHELLO\r\n--zomg--\r\n"]
       :part [{} "HELLO"]
       :part nil))

  (is (parsed
       ["\r\n\r\n--zomg\r\n\r\n"
        "HELLO\r\n--zomg--\r\n"]
       :part [{} "HELLO"]
       :part nil))

  (is (parsed
       ["\r\n\r\n--zomg\r\n\r\nHELLO\r\n--zomg-"
        "-\r\n"]
       :part [{} "HELLO"]
       :part nil)))

(deftest simple-multipart-with-headers
  (is (parsed
       (str "\r\n\r\n"
            "--zomg\r\n"
            "Content-Type: application/json\r\n"
            "\r\n"
            "[1,2,3]\r\n"
            "--zomg\r\n"
            "RECEIVED : blah fram    \r\n"
            "foo-or-BAR: bar\r\n"
            "\r\n"
            "OH MY GOD!\r\n"
            "--zomg--\r\n")
       :part [{"content-type" "application/json"} "[1,2,3]"]
       :part [{"received" "blah fram" "foo-or-bar" "bar"} "OH MY GOD!"]
       :part nil)))

(deftest chunking
  (is (parsed
       ["\r\n\r\n--zomg\r\n\r\nHEL"
        "LO\r\n--zomg\r\n\r\nGOOD"
        "BYE\r\n--zomg--\r\n"]
       :part [{} :chunked]
       :body "HEL"
       :body "LO"
       :body nil
       :part [{} :chunked]
       :body "GOOD"
       :body "BYE"
       :body nil
       :part nil)))

(deftest chunk-splits-boundary
  (is (parsed
       ["\r\n\r\n--zomg\r\n\r\nHELLO\r"
        "\n--zomg--\r\n"]
       :part [{} :chunked]
       :body "HELLO"
       :body nil
       :part nil))

  (is (parsed
       ["\r\n\r\n--zomg\r\n\r\nHELLO\r\n"
        "--zomg--\r\n"]
       :part [{} :chunked]
       :body "HELLO"
       :body nil
       :part nil))

  (is (parsed
       ["\r\n\r\n--zomg\r\n\r\nHELLO\r\n-"
        "-zomg--\r\n"]
       :part [{} :chunked]
       :body "HELLO"
       :body nil
       :part nil))

  (is (parsed
       ["\r\n\r\n--zomg\r\n\r\nHELLO\r\n--"
        "zomg--\r\n"]
       :part [{} :chunked]
       :body "HELLO"
       :body nil
       :part nil))

  (is (parsed
       ["\r\n\r\n--zomg\r\n\r\nHELLO\r\n--zo"
        "mg--\r\n"]
       :part [{} :chunked]
       :body "HELLO"
       :body nil
       :part nil))

  (is (parsed
       ["\r\n\r\n--zomg\r\n\r\nHELLO\r\n--zomg"
        "--\r\n"]
       :part [{} "HELLO"]
       :part nil))
  )

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
