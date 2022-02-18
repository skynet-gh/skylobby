(ns skylobby.sql
  (:require
    [honey.sql :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as result-set]
    [skylobby.rapid :as rapid]
    [taoensso.timbre :as log]))


(def db-config
  {:dbtype "h2"
   :dbname "skylobby"})


(defn select-rapid-query [where]
  (sql/format
    {:select [:id :hash :version :detail :spring-root]
     :from [:rapid]
     :where where}))

(defn upsert-rapid-query [rapid-data]
  (sql/format
    {:insert-into :rapid
     :values [rapid-data]
     :on-conflict :id
     :do-update-set (keys rapid-data)}))

(def merge-rapid-data-sql
  "MERGE INTO rapid(id, hash, version, detail, spring_root) KEY(id) VALUES (?, ?, ?, ?, ?)")

(defn rapid-data-param-group [rapid-data]
  [(::rapid/id rapid-data)
   (::rapid/hash rapid-data)
   (::rapid/version rapid-data)
   (::rapid/detail rapid-data)
   (::rapid/spring-root rapid-data)])

(defn merge-rapid-query [rapid-data]
  [merge-rapid-data-sql
   (::rapid/id rapid-data)
   (::rapid/hash rapid-data)
   (::rapid/version rapid-data)
   (::rapid/detail rapid-data)
   (::rapid/spring-root rapid-data)])


(def opts
  {:builder-fn result-set/as-unqualified-lower-maps})

(deftype SQLDatabase [ds]
  rapid/RapidIndex
  (rapid-id-by-hash
    [this spring-root rapid-hash]
    (:id (rapid/rapid-data-by-hash this spring-root rapid-hash)))
  (rapid-data-by-version
    [_this spring-root version]
    (let [query (select-rapid-query [:and
                                     [:= :spring-root spring-root]
                                     [:= :version version]])]
      (jdbc/execute-one! ds query opts)))
  (rapid-data-by-id
    [_this spring-root id]
    (let [query (select-rapid-query [:and
                                     [:= :spring-root spring-root]
                                     [:= :id id]])]
      (jdbc/execute-one! ds query opts)))
  (rapid-data-by-hash
    [_this spring-root rapid-hash]
    (let [query (select-rapid-query [:and
                                     [:= :spring-root spring-root]
                                     [:= :hash rapid-hash]])]
      (jdbc/execute-one! ds query opts)))
  (update-rapid-datum
    [_this spring-root rapid-datum]
    (let [data (assoc rapid-datum ::rapid/spring-root spring-root)
          query (merge-rapid-query data) ;(upsert-rapid-query data)
          _ (log/trace "Running:" (pr-str query))
          result (jdbc/execute! ds query)]
      (log/trace "Upsert result:" (pr-str result)))
    rapid-datum)
  (update-rapid-data
    [_this spring-root rapid-data]
    (with-open [conn (jdbc/get-connection ds)]
      (with-open [ps (jdbc/prepare conn [merge-rapid-data-sql])]
        (let [param-groups (mapv
                             (comp rapid-data-param-group #(assoc % ::rapid/spring-root spring-root))
                             rapid-data)
              results (jdbc/execute-batch! ps param-groups)]
          results)))))


(defn create-rapid-query []
  (sql/format
    {:create-table [:rapid :if-not-exists]
     :with-columns
     [[:id [:varchar 255]  [:not nil]]
      [:hash [:varchar 255] [:not nil]]
      [:version [:varchar 255] [:not nil]]
      [:detail [:varchar 255]]
      [:spring-root [:varchar 255]]]}))


(defn init-db
  ([state-atom]
   (init-db state-atom nil))
  ([state-atom opts]
   (let [{:keys [db use-db-for-downloadables use-db-for-rapid use-db-for-replays]} @state-atom
         sql-db-enabled (or use-db-for-downloadables
                            use-db-for-rapid
                            use-db-for-replays)]
     (when-let [ds (:ds db)]
       (log/info "Stopping sql db" db "datasource" ds)
       (.close ds))
     (if (or sql-db-enabled
             (:force opts))
       (let [ds (jdbc/get-datasource db-config)
             create-rapid-query (create-rapid-query)
             _ (log/info "Running" (pr-str create-rapid-query))
             create-rapid-result (jdbc/execute! ds create-rapid-query)
             _ (log/info "Create rapid result" (pr-str create-rapid-result))
             db (SQLDatabase. ds)]
         (log/info "Initialized SQL database" db)
         (swap! state-atom assoc :db db))
       (log/info "SQL db not enabled and not force start")))))
