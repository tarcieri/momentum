(defproject picard "0.0.1-SNAPSHOT"
  :description "Async HTTP framework built on top of Netty"
  :dependencies [[org.clojure/clojure "1.2.0"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [org.jboss.netty/netty "3.2.4.Final"]]
  :dev-dependencies [[swank-clojure "1.3.0-SNAPSHOT"]
                     [lamina "0.4.0-SNAPSHOT"]
                     [robert/hooke "1.1.0"]]
  :source-path      "src/clojure"
  :java-source-path "src/java"
  :javac-options    {:debug "on"}
  :test-selectors   {:focus (fn [v] (:focus v))
                     :all   (fn [_] true)}
  :main picard.core)
