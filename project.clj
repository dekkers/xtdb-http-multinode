(defproject xtdb-http-multinode "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [com.xtdb/xtdb-core "1.22.1"]
                 [com.xtdb/xtdb-rocksdb "1.22.1"]
                 [com.xtdb/xtdb-jdbc "1.22.1"]
                 [com.xtdb/xtdb-http-server "1.22.1"]
                 [ch.qos.logback/logback-classic "1.2.3"]
                 [org.slf4j/slf4j-api "1.7.36"]]
  :jvm-opts ["-Dlogback.configurationFile=resources/logback.xml"]
  :main ^:skip-aot xtdb-http-multinode.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
