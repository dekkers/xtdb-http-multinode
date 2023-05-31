(defproject xtdb-http-multinode "0.1.0-SNAPSHOT"
  :description "XTDB http-server with multinode support"
  :url "https://github.com/dekkers/xtdb-http-multinode"
  :license {:name "MIT"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [com.xtdb/xtdb-core "1.23.2"]
                 [com.xtdb/xtdb-rocksdb "1.23.2"]
                 [com.xtdb/xtdb-jdbc "1.23.2"]
                 [com.xtdb/xtdb-http-server "1.23.2"]
                 [ch.qos.logback/logback-classic "1.2.3"]
                 [org.slf4j/slf4j-api "1.7.36"]]
  :jvm-opts ["-Dlogback.configurationFile=resources/logback.xml"]
  :main xtdb-http-multinode.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
