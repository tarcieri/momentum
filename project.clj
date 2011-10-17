(defproject picard "0.2.0-SNAPSHOT"
  :description "Async HTTP framework built on top of Netty"

  :dependencies [[org.clojure/clojure   "1.2.0"]
                 [org.jboss.netty/netty "3.2.4.Final"]]

  :dev-dependencies [[swank-clojure "1.3.0-SNAPSHOT"]
                     [lamina        "0.4.0-SNAPSHOT"]
                     [robert/hooke  "1.1.0"]]

  :source-path      "src/clj"
  :java-source-path "src/jvm"
  :javac-options    {:debug "true"}

  :test-selectors   {:focus      (fn [v] (:focus v))
                     :no-network (fn [v] (not (:network v)))
                     :all        (fn [_] true)})
