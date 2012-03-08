(defproject io.tilde.momentum/momentum "0.3.0-SNAPSHOT"
  :description "Clojure library for high-performance server and client applications"

  :dependencies [[org.clojure/clojure "1.3.0"]]

  :source-path      "src/clj"
  :java-source-path "src/jvm"
  :javac-options    {:debug "true"}

  :test-selectors   {:focus      (fn [v] (:focus v))
                     :no-network (fn [v] (not (:network v)))
                     :all        (fn [_] true)})
