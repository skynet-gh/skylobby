(ns skylobby.sql
  (:require
    [clojure.edn :as edn]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as result-set]
    [skylobby.download :as download]
    [skylobby.import :as import]
    [skylobby.fs :as fs]
    [skylobby.rapid :as rapid]
    [skylobby.replay :as replay]
    [skylobby.util :as u]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)


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


(defn replay-param-group [replay]
  [(:replay-id replay)
   (fs/canonical-path (:file replay))
   (pr-str replay)])

(def merge-replay-sql
  "MERGE INTO replay(id, path, data) KEY(path) VALUES (?, ?, STRINGTOUTF8(?))")


(defn download-param-group [downloadable]
  [(:download-url downloadable)
   (:download-source-name downloadable)
   (pr-str downloadable)])

(def merge-download-sql
  "MERGE INTO download(url, download_source_name, data) KEY(url) VALUES (?, ?, STRINGTOUTF8(?))")


(defn import-param-group [importable]
  [(fs/canonical-path (:resource-file importable))
   (:import-source-name importable)
   (pr-str importable)])

(def merge-import-sql
  "MERGE INTO import(path, import_source_name, data) KEY(path) VALUES (?, ?, STRINGTOUTF8(?))")


(def opts
  {:builder-fn result-set/as-unqualified-lower-maps})

(defn read-edn-blob [^bytes bs]
  (let [s (u/bytes->str bs)]
    (edn/read-string {:readers u/custom-readers} s)))

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
          results))))
  replay/ReplayIndex
  (all-replays [_this]
    (let [query (sql/format
                  {:select [[:*]]
                   :from [:replay]})]
      (log/info "Running" (pr-str query))
      (mapv
        (comp read-edn-blob :data)
        (jdbc/execute! ds query opts))))
  (update-replays
    [_this replays]
    (with-open [conn (jdbc/get-connection ds)]
      (with-open [ps (jdbc/prepare conn [merge-replay-sql])]
        (let [param-groups (->> replays
                                (filter :replay-id)
                                (filter :file)
                                (mapv replay-param-group))
              results (jdbc/execute-batch! ps param-groups)]
          results))))
  download/DownloadIndex
  (all-downloadables [_this]
    (let [query (sql/format
                  {:select [[:*]]
                   :from [:download]})]
      (log/info "Running" (pr-str query))
      (mapv
        (comp read-edn-blob :data)
        (jdbc/execute! ds query opts))))
  (update-downloadables
    [_this downloadables]
    (with-open [conn (jdbc/get-connection ds)]
      (with-open [ps (jdbc/prepare conn [merge-download-sql])]
        (let [param-groups (->> downloadables
                                (filter :download-url)
                                (filter :download-source-name)
                                (mapv download-param-group))
              results (jdbc/execute-batch! ps param-groups)]
          results))))
  import/ImportIndex
  (all-importables [_this]
    (let [query (sql/format
                  {:select [[:*]]
                   :from [:import]})]
      (log/info "Running" (pr-str query))
      (mapv
        (comp read-edn-blob :data)
        (jdbc/execute! ds query opts))))
  (update-importables
    [_this importables]
    (with-open [conn (jdbc/get-connection ds)]
      (with-open [ps (jdbc/prepare conn [merge-import-sql])]
        (let [param-groups (->> importables
                                (filter :resource-file)
                                (filter :import-source-name)
                                (mapv import-param-group))
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
      [:spring-root [:varchar 255] [:not nil]]]}))

(defn create-replay-query []
  (sql/format
    {:create-table [:replay :if-not-exists]
     :with-columns
     [[:id [:varchar 255]  [:not nil]]
      [:path [:varchar 255] [:not nil]]
      [:data :varbinary]]}))

(defn create-download-query []
  (sql/format
    {:create-table [:download :if-not-exists]
     :with-columns
     [[:url [:varchar 255]  [:not nil]]
      [:download-source-name [:varchar 255] [:not nil]]
      [:data :varbinary]]}))

(defn create-import-query []
  (sql/format
    {:create-table [:import :if-not-exists]
     :with-columns
     [[:path [:varchar 255]  [:not nil]]
      [:import-source-name [:varchar 255] [:not nil]]
      [:data :varbinary]]}))


(defn create-table [ds query]
  (log/info "Running" (pr-str query))
  (let [result (jdbc/execute! ds query)]
    (log/info "Create result" (pr-str result))))

(defn init-db
  ([state-atom]
   (init-db state-atom nil))
  ([state-atom opts]
   (let [{:keys [db use-db-for-downloadables use-db-for-importables use-db-for-rapid use-db-for-replays]} @state-atom
         sql-db-enabled (or use-db-for-downloadables
                            use-db-for-importables
                            use-db-for-rapid
                            use-db-for-replays)]
     (when-let [ds (:ds db)]
       (log/info "Stopping sql db" db "datasource" ds)
       (.close ds))
     (if (or sql-db-enabled
             (:force opts))
       (let [ds (jdbc/get-datasource db-config)
             db (SQLDatabase. ds)]
         (create-table ds (create-rapid-query))
         (create-table ds (create-replay-query))
         (create-table ds (create-download-query))
         (create-table ds (create-import-query))
         (log/info "Initialized SQL database" db)
         (swap! state-atom assoc :db db))
       (log/info "SQL db not enabled and not force start")))))
