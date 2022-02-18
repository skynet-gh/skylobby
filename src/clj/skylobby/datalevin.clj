(ns skylobby.datalevin
  (:require
    [datalevin.core :as datalevin]
    [taoensso.timbre :as log]))


(defn rapid-id-by-hash [conn spring-root-path rapid-hash]
  (try
    (first
      (datalevin/q
        '[:find [?i]
          :in $ ?spring-root-path ?hash
          :where
          [?id :skylobby.rapid/spring-root ?spring-root-path]
          [?id :skylobby.rapid/hash ?hash]
          [?id :skylobby.rapid/id ?i]]
        (datalevin/db conn)
        spring-root-path
        rapid-hash))
    (catch Exception e
      (log/error e "Error getting rapid id by hash in" spring-root-path "by" rapid-hash))))


(defn rapid-data-by-id [conn spring-root-path id]
  (try
    (when-let [[rapid-hash version] (datalevin/q
                                      '[:find [?h ?v]
                                        :in $ ?spring-root-path ?rapid-id
                                        :where
                                        [?id :skylobby.rapid/spring-root ?spring-root-path]
                                        [?id :skylobby.rapid/id ?rapid-id]
                                        [?id :skylobby.rapid/hash ?h]
                                        [?id :skylobby.rapid/version ?v]]
                                      (datalevin/db conn)
                                      spring-root-path
                                      id)]
      {:id id
       :hash rapid-hash
       :version version})
    (catch Exception e
      (log/error e "Error getting rapid data by id in" spring-root-path "for" id))))

(defn rapid-data-by-version [conn spring-root-path version]
  (try
    (when-let [[id rapid-hash] (datalevin/q
                                 '[:find [?i ?h]
                                   :in $ ?spring-root-path ?version
                                   :where
                                   [?id :skylobby.rapid/spring-root ?spring-root-path]
                                   [?id :skylobby.rapid/version ?version]
                                   [?id :skylobby.rapid/id ?i]
                                   [?id :skylobby.rapid/hash ?h]]
                                 (datalevin/db conn)
                                 spring-root-path
                                 version)]
      {:id id
       :hash rapid-hash
       :version version})
    (catch Exception e
      (log/error e "Error getting rapid data by version in" spring-root-path "for" version))))

(defn rapid-data-by-hash [conn spring-root-path rapid-hash]
  (try
    (when-let [[id version] (datalevin/q
                              '[:find [?i ?v]
                                :in $ ?spring-root-path ?hash
                                :where
                                [?id :skylobby.rapid/spring-root ?spring-root-path]
                                [?id :skylobby.rapid/hash ?hash]
                                [?id :skylobby.rapid/id ?i]
                                [?id :skylobby.rapid/version ?v]]
                              (datalevin/db conn)
                              spring-root-path
                              rapid-hash)]
      {:id id
       :hash rapid-hash
       :version version})
    (catch Exception e
      (log/error e "Error getting rapid data by hash in" spring-root-path "for" rapid-hash))))
