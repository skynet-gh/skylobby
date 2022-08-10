(ns skylobby.main.resources
  (:require
    [clojure.tools.cli :as cli]
    [skylobby.auto-resources :as auto-resources]
    [skylobby.cli.util :as cu]
    skylobby.core
    [skylobby.task :as task]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)


(def cli-options
  [
   [nil "--engine ENGINE" "Engine to get"]
   [nil "--game GAME" "Game to get"]
   [nil "--map MAP" "Map to get"]])

(def max-tries 10)

(defn -main [& args]
  (let [{:keys [errors options]} (cli/parse-opts args cli-options :in-order true)
        {engine-version :engine mod-name :game map-name :map} options]
    (cond
      errors
      (apply cu/print-and-exit -1
        "Error parsing arguments:\n"
        errors)
      (not (or engine-version mod-name map-name))
      (cu/print-and-exit -1 "At least one of [--engine, --map, --mod] is required")
      :else
      (let [
            _ (log/info "Loading initial state")
            initial-state (skylobby.core/initial-state)
            state (merge
                    initial-state
                    {:ipc-server-enabled false
                     :use-db-for-rapid false
                     :use-db-for-replays false})
            tries (atom 0)
            resources {:engine-version engine-version
                       :map-name map-name
                       :mod-name mod-name}
            has-all-resources (fn [state]
                                (let [{:keys [engine-details map-details mod-details]} (auto-resources/resource-details resources state)]
                                  (and 
                                    (when engine-version engine-details)
                                    (when map-name map-details)
                                    (when mod-name mod-details))))
            add-tasks (fn [state]
                        (let [tasks (auto-resources/auto-resources-tasks
                                      (assoc resources :battle-changed true)
                                      state)]
                          (if (seq tasks)
                            (task/add-tasks! skylobby.core/*state tasks)
                            (log/error "No tasks to auto-get resources"))))
            exit (fn [code message]
                   (skylobby.core/spit-state-config-to-edn nil @skylobby.core/*state)
                   (cu/print-and-exit code message))]
        (when (has-all-resources state)
          (cu/print-and-exit 0 "All resources gotten"))
        (reset! skylobby.core/*state state)
        (skylobby.core/init skylobby.core/*state {:initial-task-delay-ms 0
                                                  :skip-tasks true})
        (add-watch skylobby.core/*state :retry-when-no-tasks
          (fn [_ _ _ new-state]
            (let [all-tasks (task/all-tasks new-state)]
              (if (empty? all-tasks)
                (cond
                  (has-all-resources new-state) (exit 0 "All resources gotten")
                  (>= @tries max-tries) (exit 1 "Max tries reached")
                  :else
                  (do
                    (log/info "No tasks, trying to get resources")
                    (swap! tries inc)
                    (add-tasks new-state)))
                (log/info "Waiting for" (count all-tasks) "tasks before retrying")))))
        (add-tasks @skylobby.core/*state)))))
