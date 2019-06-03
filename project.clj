(defproject com.fulcrologic/fulcro-websockets "3.0.0-SNAPSHOT"
  :description "A pluggable websocket remote for Fulcro 3+"
  :url "https://github.com/fulcrologic/fulcro3-websockets"
  :lein-min-version "2.8.1"
  :license {:name "MIT"
            :url  "https://opensource.org/licenses/MIT"}

  :source-paths ["src/main"]

  :plugins [[lein-tools-deps "0.4.1"]]
  :middleware [lein-tools-deps.plugin/resolve-dependencies-with-deps-edn]
  :lein-tools-deps/config {:config-files [:install :user :project]})
