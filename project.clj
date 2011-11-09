(defproject momentum "0.2.0-SNAPSHOT"
  :description "Async HTTP framework built on top of Netty"

  :dependencies [[org.clojure/clojure   "1.3.0"]
                 [org.jboss.netty/netty "3.2.4.Final"]]

  :dev-dependencies [[swank-clojure "1.3.4-SNAPSHOT" :exclusions [org.clojure/clojure]]
                     [robert/hooke  "1.1.2"]]

  :source-path      "src/clj"
  :java-source-path "src/jvm"
  :javac-options    {:debug "true"}

  :test-selectors   {:focus      (fn [v] (:focus v))
                     :no-network (fn [v] (not (:network v)))
                     :all        (fn [_] true)})
