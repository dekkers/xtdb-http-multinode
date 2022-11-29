(ns xtdb-http-multinode.core
  (:require [juxt.clojars-mirrors.camel-snake-kebab.v0v4v2.camel-snake-kebab.core :as csk]
            [clojure.edn :as edn]
            [clojure.instant :as instant]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [xtdb.api :as xt]
            [xtdb.http-server.entity :as entity]
            [xtdb.http-server.json :as http-json]
            [xtdb-http-multinode.query :as query]
            [xtdb-http-multinode.status :as status]
            [xtdb.http-server.util :as util]
            [xtdb.io :as xio]
            [xtdb.rocksdb :as rocks]
            [xtdb.system :as sys]
            [xtdb.tx :as tx]
            [xtdb.tx.conform :as txc]
            [juxt.clojars-mirrors.jsonista.v0v3v5.jsonista.core :as json]
            [juxt.clojars-mirrors.muuntaja.v0v6v8.muuntaja.core :as m]
            [juxt.clojars-mirrors.muuntaja.v0v6v8.muuntaja.format.core :as mfc]
            [juxt.clojars-mirrors.reitit-spec.v0v5v15.reitit.coercion.spec :as reitit.spec]
            [juxt.clojars-mirrors.reitit-ring.v0v5v15.reitit.ring :as rr]
            [juxt.clojars-mirrors.reitit-ring.v0v5v15.reitit.ring.coercion :as rrc]
            [juxt.clojars-mirrors.reitit-middleware.v0v5v15.reitit.ring.middleware.exception :as re]
            [juxt.clojars-mirrors.reitit-middleware.v0v5v15.reitit.ring.middleware.muuntaja :as rm]
            [juxt.clojars-mirrors.reitit-swagger.v0v5v15.reitit.swagger :as swagger]
            [juxt.clojars-mirrors.ring-jetty-adapter.v0v14v2.ring.adapter.jetty9 :as j]
            [juxt.clojars-mirrors.ring-core.v1v9v4.ring.middleware.params :as p]
            [juxt.clojars-mirrors.ring-core.v1v9v4.ring.util.response :as resp]
            [juxt.clojars-mirrors.ring-core.v1v9v4.ring.util.time :as rt])
  (:import [com.nimbusds.jose.crypto ECDSAVerifier RSASSAVerifier]
           [com.nimbusds.jose.jwk ECKey JWKSet KeyType RSAKey]
           com.nimbusds.jwt.SignedJWT
           [xtdb.api NodeOutOfSyncException]
           [java.io Closeable IOException]
           java.time.Duration
           org.eclipse.jetty.server.Server)
  (:gen-class))

(defn- add-last-modified [response date]
  (cond-> response
    date (assoc-in [:headers "Last-Modified"] (rt/format-date date))))

(s/def ::db-spec (s/keys :opt-un [::util/valid-time ::util/tx-time ::util/tx-id]))

(defn- db-handler [req]
  (let [{:keys [valid-time tx-time tx-id]} (get-in req [:parameters :query])
        xtdb-node (get req :xtdb-node)
        db (util/db-for-request xtdb-node {:valid-time valid-time
                                           :tx-time tx-time
                                           :tx-id tx-id})]
    (resp/response (merge {::xt/valid-time (xt/valid-time db)}
                          (xt/db-basis db)))))

(s/def ::entity-tx-spec (s/keys :req-un [(or ::util/eid-edn ::util/eid-json ::util/eid)]
                                :opt-un [::util/valid-time ::util/tx-time ::util/tx-id]))

(defn- entity-tx [req]
  (let [{:keys [eid eid-edn eid-json valid-time tx-time tx-id]} (get-in req [:parameters :query])
        xtdb-node (get req :xtdb-node)
        eid (or eid-edn eid-json eid)
        db (util/db-for-request xtdb-node {:valid-time valid-time
                                           :tx-time tx-time
                                           :tx-id tx-id})
        entity-tx (xt/entity-tx db eid)]
    (if entity-tx
      (-> {:status 200
           :body entity-tx}
          (add-last-modified (::xt/tx-time entity-tx)))
      {:status 404
       :body {:error (str eid " entity-tx not found")}})))

(defn- ->submit-json-decoder [_]
  (let [decoders {::txc/->doc #(xio/update-if % :xt/fn edn/read-string)
                  ::txc/->valid-time (fn [vt-str]
                                       (try
                                         (instant/read-instant-date vt-str)
                                         (catch Exception _e
                                           vt-str)))}]
    (reify
      mfc/Decode
      (decode [_ data _]
        (-> (json/read-value data http-json/xtdb-object-mapper)
            (update :tx-ops (fn [tx-ops]
                              (->> tx-ops
                                   (mapv (fn [tx-op]
                                           (-> tx-op
                                               (update 0 (fn [op] (keyword "xtdb.api" op)))
                                               (txc/conform-tx-op decoders)
                                               (txc/->tx-op))))))))))))

(def ->submit-tx-muuntaja
  (m/create
   (assoc-in (util/->default-muuntaja {:json-encode-fn http-json/camel-case-keys})
             [:formats "application/json" :decoder]
             [->submit-json-decoder])))

(s/def ::tx-ops vector?)

(s/def ::submit-tx-spec
  (s/keys :req-un [::tx-ops]
          :opt [::xt/submit-tx-opts]))

(defn- submit-tx [req]
    (let [{:keys [tx-ops ::xt/submit-tx-opts]} (get-in req [:parameters :body])
          xtdb-node (get req :xtdb-node)
          {::xt/keys [tx-time] :as submitted-tx} (xt/submit-tx xtdb-node tx-ops submit-tx-opts)]
      (-> {:status 202
           :body submitted-tx}
          (add-last-modified tx-time))))

(s/def ::with-ops? boolean?)
(s/def ::after-tx-id int?)
(s/def ::tx-log-spec (s/keys :opt-un [::with-ops? ::after-tx-id]))

(defn txs->json [txs]
  (mapv #(update % 0 name) txs))

(defn tx-log-json-encode [tx]
  (-> tx
      (xio/update-if ::xt/tx-ops txs->json)
      (xio/update-if ::xt/tx-events txs->json)
      (http-json/camel-case-keys)))

(def ->tx-log-muuntaja
  (m/create
   (-> (util/->default-muuntaja {:json-encode-fn tx-log-json-encode})
       (assoc :return :output-stream))))

(defn- tx-log [req]
  (let [{:keys [with-ops? after-tx-id]} (get-in req [:parameters :query])
        xtdb-node (get req :xtdb-node)]
    (-> {:status 200
         :body {:results (xt/open-tx-log xtdb-node after-tx-id with-ops?)}
         :return :output-stream}
        (add-last-modified (::xt/tx-time (xt/latest-completed-tx xtdb-node))))))

(s/def ::sync-spec (s/keys :opt-un [::util/tx-time ::util/timeout]))

(defn- sync-handler [req]
  (let [{:keys [timeout tx-time]} (get-in req [:parameters :query])
        xtdb-node (get req :xtdb-node)
        timeout (some-> timeout (Duration/ofMillis))
        last-modified (if tx-time
                        (xt/await-tx-time xtdb-node tx-time timeout)
                        (xt/sync xtdb-node timeout))]
    (-> {:status 200
         :body {::xt/tx-time last-modified}}
        (add-last-modified last-modified))))

(s/def ::await-tx-time-spec (s/keys :req-un [::util/tx-time] :opt-un [::util/timeout]))

(defn- await-tx-time-handler [req]
  (let [{:keys [timeout tx-time]} (get-in req [:parameters :query])
        xtdb-node (get req :xtdb-node)
        timeout (some-> timeout (Duration/ofMillis))
        last-modified (xt/await-tx-time xtdb-node tx-time timeout)]
    (-> {:status 200
         :body {::xt/tx-time last-modified}}
        (add-last-modified last-modified))))

(s/def ::await-tx-spec (s/keys :req-un [::util/tx-id] :opt-un [::util/timeout]))

(defn- await-tx-handler [req]
  (let [{:keys [timeout tx-id]} (get-in req [:parameters :query])
        xtdb-node (get req :xtdb-node)
        timeout (some-> timeout (Duration/ofMillis))
        {::xt/keys [tx-time] :as tx} (xt/await-tx xtdb-node {::xt/tx-id tx-id} timeout)]
    (-> {:status 200, :body tx}
        (add-last-modified tx-time))))

(defn- attribute-stats [req]
  {:status 200
   :body (xt/attribute-stats (get req :xtdb-node))})

(s/def ::tx-committed-spec (s/keys :req-un [::util/tx-id]))

(defn- tx-committed? [req]
  (try
    (let [tx-id (get-in req [:parameters :query :tx-id])
          xtdb-node (get req :xtdb-node)]
      {:status 200
       :body {:tx-committed? (xt/tx-committed? xtdb-node {::xt/tx-id tx-id})}})
    (catch NodeOutOfSyncException e
      {:status 400, :body e})))

(defn latest-completed-tx [req]
  (if-let [latest-completed-tx (xt/latest-completed-tx (get req :xtdb-node))]
    {:status 200
     :body latest-completed-tx}
    {:status 404
     :body {:error "No latest-completed-tx found."}}))

(defn latest-submitted-tx [req]
  (if-let [latest-submitted-tx (xt/latest-submitted-tx (get req :xtdb-node))]
    {:status 200
     :body latest-submitted-tx}
    {:status 404
     :body {:error "No latest-submitted-tx found."}}))

(defn active-queries [req]
  {:status 200
   :body (xt/active-queries (get req :xtdb-node))})

(defn recent-queries [req]
  {:status 200
   :body (xt/recent-queries (get req :xtdb-node))})

(defn slowest-queries [req]
  {:status 200
   :body (xt/slowest-queries (get req :xtdb-node))})

(def ^:private sparql-available?
  (try ; you can change it back to require when clojure.core fixes it to be thread-safe
    (requiring-resolve 'xtdb.sparql.protocol/sparql-query)
    true
    (catch IOException _
      false)))

(defn sparqql [req]
  (when sparql-available?
    ((resolve 'xtdb.sparql.protocol/sparql-query) (get req :xtdb-node) req)))

(defn- add-response-format [handler format]
  (fn [req]
    (-> (handler (assoc-in req [:muuntaja/response :format] format))
        (assoc :muuntaja/content-type format))))

(def ^:const default-server-port 3000)

(defrecord HTTPServer [^Server server options]
  Closeable
  (close [_]
    (.stop server)))

(defn valid-jwt?
  "Return true if the given JWS is valid with respect to the given
  signing key."
  [^String jwt ^JWKSet jwks]
  (try
    (let [jws (SignedJWT/parse ^String jwt)
          kid (.. jws getHeader getKeyID)
          jwk (.getKeyByKeyId jwks kid)
          verifier (case (.getValue ^KeyType (.getKeyType jwk))
                     "RSA" (RSASSAVerifier. ^RSAKey jwk)
                     "EC"  (ECDSAVerifier. ^ECKey jwk))]
      (.verify jws verifier))
    (catch Exception _
      false)))

(defn wrap-jwt [handler jwks]
  (fn [request]
    (if-not (valid-jwt? (or (get-in request [:headers "x-amzn-oidc-accesstoken"])
                            (some->> (get-in request [:headers "authorization"])
                                     (re-matches #"Bearer (.*)")
                                     (second)))
                        jwks)
      {:status 401
       :body "JWT Failed to validate"}

      (handler request))))

(defn handle-ex-info [ex req]
  {:status 400
   :body (ex-data ex)})

(defn handle-muuntaja-decode-error [ex req]
  {:status 400
   :body {:error (str "Malformed " (-> ex ex-data :format pr-str) " request.") }})

(defn wrap-camel-case-params [handler]
  (fn [{:keys [query-params] :as request}]
    (let [kebab-qps (into {} (map (fn [[k v]] [(csk/->kebab-case k) v])) query-params)]
      (handler (assoc request :query-params kebab-qps)))))

(defn- query-list-json-encode [query-states]
  (map (fn [qs]
         (-> qs
             (update :query pr-str)
             http-json/camel-case-keys))
       query-states))

(def ^:private query-list-muuntaja
  (m/create (util/->default-muuntaja {:json-encode-fn query-list-json-encode})))

(def default-muuntaja
  (m/create (util/->default-muuntaja {:json-encode-fn http-json/camel-case-keys})))

(defn- make-cursors [example]
  (let [example-meta (meta example)]
    (cond
      (:results-cursor example-meta) {:results (xio/->cursor #() example)}
      :else example)))

(def ^java.io.File node-dir
  (io/file (or (System/getenv "XTDB_DATA_DIR") "/var/lib/xtdb")))

(def nodes (atom {}))
(defn- atom-add-node [nodes name]
  (if (get nodes name)
    ;; Node was already added, so we can just return nodes
    nodes
    (let [node-config {:xtdb/index-store {:kv-store {:xtdb/module `rocks/->kv-store,
                                                     :db-dir (io/file node-dir name "indexes"),
                                                     :block-cache :xtdb.rocksdb/block-cache}}
                       :xtdb/document-store {:kv-store {:xtdb/module `rocks/->kv-store,
                                                        :db-dir (io/file node-dir name "documents")
                                                        :block-cache :xtdb.rocksdb/block-cache}}
                       :xtdb/tx-log {:kv-store {:xtdb/module `rocks/->kv-store,
                                                :db-dir (io/file node-dir name "tx-log")
                                                :block-cache :xtdb.rocksdb/block-cache}}
                       :xtdb.rocksdb/block-cache {:xtdb/module `rocks/->lru-block-cache
                                                  :cache-size (* 128 1024 1024)}}]
      (log/debug "Starting node" name)
      (assoc nodes name (xt/start-node node-config)))))

(defn- start-nodes []
  (loop [paths (.list node-dir)]
    (if (empty? paths)
      nil
      (let [path (first paths)]
        ;; A few sanity checks
        (if (or (not (.isDirectory (io/file node-dir path)))
                (not (.isDirectory (io/file node-dir path "indexes")))
                (not (.isDirectory (io/file node-dir path "documents")))
                (not (.isDirectory (io/file node-dir path "tx-log"))))
          (log/error "Node directory contains" path "which isn't a a valid node")
          (do
            (log/info "Starting node" path)
            (swap! nodes atom-add-node path)))
        (recur (rest paths))))))

(s/def :xtdb/node string?)

(s/def ::node-name-spec
  (s/keys :req-un [:xtdb/node]))

(defn- create-node [request]
  (let [name (get-in request [:parameters :body :node])]
    (swap! nodes atom-add-node name)
    (log/info "Created node" name)
    {:status 200, :body {:created true}}))

(defn- atom-delete-node [nodes name]
  (let [node (get nodes name)]
    (if (not node)
      ;; Node is not there, so nothing to delete
      nodes
      (do
        (.close node)
        (run! io/delete-file (reverse (file-seq (io/file node-dir name))))
        (dissoc nodes name)))))

(defn- delete-node [request]
  (let [name (get-in request [:parameters :body :node])]
    (if (get @nodes name)
      (do
        (swap! nodes atom-delete-node name)
        (log/info "Deleted node" name)
        {:status 200, :body {:deleted true}})
      {:status 404, :body {:error "Node not found"}})))

(defn- lookup-node [handler]
  (fn [request]
    (let [node-name (get-in request [:path-params :node])]
      (if node-name
        (let [node (get @nodes node-name)]
          (if node
            (handler (assoc request :xtdb-node node))
            {:status 404, :body {:error "Node not found"}}))
        (handler request)))))

(defn- with-example [{:keys [muuntaja] :or {muuntaja default-muuntaja} :as handler} example-filename]
  (let [example (-> (io/resource (format "xtdb_http_multinode/examples/%s.edn" example-filename))
                    slurp
                    read-string)]
    (-> handler
        (assoc-in [:responses 200]
                  {:examples
                   {"application/json" (-> (m/encode muuntaja "application/json" (make-cursors example))
                                           (m/slurp)
                                           (json/read-value))
                    "application/edn" (with-out-str (pp/pprint example))
                    "application/transit+json" (-> (m/encode muuntaja "application/transit+json" (make-cursors example))
                                                   (m/slurp)
                                                   (json/read-value))}}))))

(defn- ->xtdb-router [{{:keys [^String jwks, read-only?]} :http-options
                       :as opts}]
  (let [opts (-> opts (update :http-options dissoc :jwks))
        query-handler {:muuntaja (query/->query-muuntaja opts)
                       :summary "Query"
                       :description "Perform a datalog query"
                       :get {:handler (query/data-browser-query opts)
                             :parameters {:query ::query/query-params}}
                       :post {:handler (query/data-browser-query opts)
                              :parameters {:query ::query/query-params
                                           :body ::query/body-params}}}]
    (rr/router [["/" {:no-doc true
                      :get (fn [_] (resp/redirect "/_xtdb/query"))}]
                ["/_xtdb"
                 ["/create-node" (-> {:post create-node
                                      :parameters {:body ::node-name-spec}
                                      :summary "Create Node"
                                      :description "Create new node"}
                                     (with-example "create-node"))]
                 ["/delete-node" (-> {:post delete-node
                                      :parameters {:body ::node-name-spec}
                                      :summary "Delete Node"
                                      :description "Delete node"}
                                     (with-example "delete-node"))]
                 ["/:node/db" (-> {:get db-handler
                                   :parameters {:query ::db-spec}
                                   :summary "DB"
                                   :description "Get the resolved db-basis for the given valid-time/transactoin"}
                                  (with-example "db-response"))]
                 ["/:node/status" (-> {:muuntaja (status/->status-muuntaja opts)
                                       :summary "Status"
                                       :description "Get status information from the node"
                                       :get (status/status opts)}
                                      (with-example "status-response"))]
                 ["/:node/entity" (-> {:muuntaja (entity/->entity-muuntaja opts)
                                       :summary "Entity"
                                       :description "Get information about a particular entity"
                                       :get entity/entity-state
                                       :parameters {:query ::entity/query-params}}
                                      (with-example "entity-response"))]
                 ["/:node/query" (-> query-handler
                                     (with-example "query-response"))]
                 ["/:node/query.csv" (assoc query-handler :middleware [[add-response-format "text/csv"]] :no-doc true)]
                 ["/:node/query.tsv" (assoc query-handler :middleware [[add-response-format "text/tsv"]] :no-doc true)]
                 ["/:node/entity-tx" (-> {:get entity-tx
                                          :summary "Entity Tx"
                                          :description "Get transactional information an particular entity"
                                          :parameters {:query ::entity-tx-spec}}
                                         (with-example "entity-tx-response"))] ; getest
                 ["/:node/attribute-stats" (-> {:get attribute-stats
                                                :summary "Attribute Stats"
                                                :description "Get frequencies of indexed attributes"
                                                :muuntaja (m/create (util/->default-muuntaja {:json-encode-fn identity}))}
                                               (with-example "attribute-stats-response"))]
                 ["/:node/sync" (-> {:get sync-handler
                                     :summary "Sync"
                                     :description "Wait until the Kafka consumer’s lag is back to 0"
                                     :parameters {:query ::sync-spec}}
                                    (with-example "sync-response"))]
                 ["/:node/await-tx" (-> {:get await-tx-handler
                                         :summary "Await Tx"
                                         :description "Wait until the node has indexed a transaction at or past the supplied tx-id"
                                         :parameters {:query ::await-tx-spec}}
                                        (with-example "await-tx-response"))]
                 ["/:node/await-tx-time" (-> {:get await-tx-time-handler
                                              :summary "Await Tx Time"
                                              :description "Wait until the node has indexed a transaction that is past the supplied tx-time"
                                              :parameters {:query ::await-tx-time-spec}}
                                             (with-example "await-tx-time-response"))]
                 ["/:node/tx-log" (-> {:get tx-log
                                       :summary "Tx Log"
                                       :description "Get a list of all transactions"
                                       :muuntaja ->tx-log-muuntaja
                                       :parameters {:query ::tx-log-spec}}
                                      (with-example "tx-log-response"))]
                 ["/:node/submit-tx" (-> {:muuntaja ->submit-tx-muuntaja
                                          :summary "Submit Tx"
                                          :description "Takes a vector of transactions - Writes to the node"
                                          :post (if read-only?
                                                  (fn [_] {:status 403
                                                           :body "forbidden: read-only HTTP node"})
                                                  submit-tx)
                                          :parameters {:body ::submit-tx-spec}}
                                         (with-example "submit-tx-response"))]
                 ["/:node/tx-committed" (-> {:get tx-committed?
                                             :summary "Tx Committed"
                                             :description "Checks if a submitted tx was successfully committed"
                                             :parameters {:query ::tx-committed-spec}}
                                            (with-example "tx-committed-response"))]
                 ["/:node/latest-completed-tx" (-> {:get latest-completed-tx
                                                    :summary "Latest Completed Tx"
                                                    :description "Get the latest transaction to have been indexed by this node"}
                                                   (with-example "latest-completed-tx-response"))]
                 ["/:node/latest-submitted-tx" (-> {:get latest-submitted-tx
                                                    :summary "Latest Submitted Tx"
                                                    :description "Get the latest transaction to have been submitted to this cluster"}
                                                   (with-example "latest-submitted-tx-response"))]
                 ["/:node/active-queries" (-> {:get active-queries
                                               :summary "Active Queries"
                                               :description "Get a list of currently running queries"
                                               :muuntaja query-list-muuntaja}
                                              (with-example "active-queries-response"))]
                 ["/:node/recent-queries" (-> {:get recent-queries
                                               :summary "Recent Queries"
                                               :description "Get a list of recently completed/failed queries"
                                               :muuntaja query-list-muuntaja}
                                              (with-example "recent-queries-response"))]
                 ["/:node/slowest-queries" (-> {:get slowest-queries
                                                :summary "Slowest Queries"
                                                :description "Get a list of slowest completed/failed queries ran on the node"
                                                :muuntaja query-list-muuntaja}
                                               (with-example "slowest-queries-response"))]
                 ["/:node/sparql" {:get sparqql
                                   :post sparqql
                                   :no-doc true}]

                 ["/swagger.json"
                  {:get {:no-doc true
                         :swagger {:info {:title "XTDB API"}}
                         :handler (swagger/create-swagger-handler)
                         :muuntaja (m/create (assoc (util/->default-muuntaja {}) :default-format "application/json"))}}]]]

               {:data
                {:muuntaja default-muuntaja
                 :coercion reitit.spec/coercion
                 :middleware (cond-> [p/wrap-params
                                      wrap-camel-case-params
                                      rm/format-negotiate-middleware
                                      rm/format-response-middleware
                                      (re/create-exception-middleware
                                       (merge re/default-handlers
                                              {xtdb.IllegalArgumentException handle-ex-info
                                               xtdb.api.NodeOutOfSyncException handle-ex-info
                                               :muuntaja/decode handle-muuntaja-decode-error}))
                                      rm/format-request-middleware
                                      rrc/coerce-response-middleware
                                      rrc/coerce-request-middleware
                                      lookup-node
                                      ]
                               jwks (conj #(wrap-jwt % (JWKSet/parse jwks))))}})))

(defn -main
  [& args]
  (start-nodes)
  (let [port default-server-port
        server (j/run-jetty (rr/ring-handler (->xtdb-router {:http-options {}})
                                             (rr/routes
                                              (rr/create-resource-handler {:path "/"})
                                              (rr/create-default-handler)))
                            {:port port
                             :h2c? true
                             :h2? true
                             :join? false})]
    (log/info "HTTP server started on port: " port)
    (->HTTPServer server {})))
