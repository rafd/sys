(defproject com.github.rafd/sys "0.2.1"
  :description "A boring dependency injection system for Clojure(Script) apps."
  :url "https://github.com/rafd/sys"
  :license {:name "MIT"}

  :dependencies [[org.clojure/clojure "1.12.0"]
                 [metosin/malli "0.20.0"]]

  :profiles {:test-e2e
             {:dependencies [[http-kit "2.8.1"]
                             [com.github.seancorfield/next.jdbc "1.3.1070"]
                             [com.h2database/h2 "2.4.240"]
                             [hikari-cp "2.13.0"]]}})
