(defproject picard "0.1.0-SNAPSHOT"
  :description "Async HTTP framework built on top of Netty"

  :dependencies [[org.clojure/clojure         "1.2.0"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [org.jboss.netty/netty       "3.2.4.Final"]
                 [log4j/log4j                 "1.2.16"]]

  :dev-dependencies [[swank-clojure "1.3.0-SNAPSHOT"]
                     [lamina        "0.4.0-SNAPSHOT"]
                     [robert/hooke  "1.1.0"]]

  :source-path      "src/clojure"
  :java-source-path "src/java"
  :aot              [picard.log4j.CommonLogFormatLayout
                     picard.log4j.VerboseLayout
                     picard.exceptions.PoolFullException]

  :test-selectors   {:focus      (fn [v] (:focus v))
                     :no-network (fn [v] (not (:network v)))
                     :all        (fn [_] true)}

  :repositories { "snapshots" "file:./build/snapshots"
                  "releases"  "file:./build/releases" }

)
