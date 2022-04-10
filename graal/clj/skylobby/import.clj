(ns skylobby.import
  (:require
    [clojure.string :as string]
    [skylobby.fs :as fs]
    [skylobby.rapid :as rapid]
    [skylobby.resource :as resource]
    [skylobby.task :as task]
    [skylobby.util :as u]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)


(defprotocol ImportIndex
  (all-importables [this])
  (importable-by-url [this url])
  (importables-by-source [this import-source])
  (update-importable [this importable])
  (update-importables [this importables]))


(defn import-sources [extra-import-sources]
  (concat
    [{:import-source-name "Spring"
      :file (fs/spring-root)
      :builtin true}
     {:import-source-name "Beyond All Reason"
      :file (fs/bar-root)
      :builtin true}
     {:import-source-name "Zero-K"
      :file (fs/zerok-root)
      :builtin true}]
    extra-import-sources))

(defn- importable-data [resource-type import-source-name now resource-file]
  {:resource-type resource-type
   :import-source-name import-source-name
   :resource-file resource-file
   :resource-filename (fs/filename resource-file)
   :resource-updated now})

(defn scan-imports
  [state-atom {root :file import-source-name :import-source-name}]
  (log/info "Scanning for possible imports from" root)
  (let [map-files (fs/map-files root)
        mod-files (fs/mod-files root)
        engine-dirs (fs/engine-dirs root)
        sdp-files (rapid/sdp-files root)
        now (u/curr-millis)
        importables (concat
                      (map (partial importable-data :spring-lobby/map import-source-name now) map-files)
                      (map (partial importable-data :spring-lobby/mod import-source-name now) (concat mod-files sdp-files))
                      (map (partial importable-data :spring-lobby/engine import-source-name now) engine-dirs))
        importables-by-path (->> importables
                                 (map (juxt (comp fs/canonical-path :resource-file) identity))
                                 (into {}))
        {:keys [db use-db-for-importables]} @state-atom]
    (log/info "Found imports" (frequencies (map :resource-type importables)) "from" import-source-name)
    (if (and db use-db-for-importables)
      (do
        (update-importables db importables)
        (swap! state-atom dissoc :importables-by-path))
      (swap! state-atom update :importables-by-path
             (fn [old]
               (->> old
                    (remove (comp #{import-source-name} :import-source-name second))
                    (into {})
                    (merge importables-by-path)))))
    importables-by-path))


(defn- update-copying [state-atom f copying]
  (if f
    (swap! state-atom update-in [:copying (fs/canonical-path f)] merge copying)
    (log/warn "Attempt to update copying for nil file")))

(defn import-resource
  [state-atom {:keys [importable spring-isolation-dir]}]
  (let [{:keys [resource-file]} importable
        source resource-file
        dest (resource/resource-dest spring-isolation-dir importable)]
    (update-copying state-atom source {:status true})
    (update-copying state-atom dest {:status true})
    (try
      (if (string/ends-with? (fs/filename source) ".sdp")
        (rapid/copy-package source (fs/parent-file (fs/parent-file dest)))
        (fs/copy source dest))
      (log/info "Finished importing" importable "from" source "to" dest)
      (catch Exception e
        (log/error e "Error importing" importable))
      (finally
        (update-copying state-atom source {:status false})
        (update-copying state-atom dest {:status false})
        (fs/update-file-cache! state-atom source dest)
        (case (:resource-type importable)
          :spring-lobby/map
          (task/add-task! state-atom {:spring-lobby/task-type :spring-lobby/refresh-maps})
          :spring-lobby/mod
          (task/add-task! state-atom {:spring-lobby/task-type :spring-lobby/refresh-mods})
          :spring-lobby/engine
          (task/add-task! state-atom {:spring-lobby/task-type :spring-lobby/refresh-engines})
          nil)))))
