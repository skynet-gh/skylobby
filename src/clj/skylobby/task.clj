(ns skylobby.task)


(def index-tasks
  #{:spring-lobby/reconcile-engines
    :spring-lobby/reconcile-mods
    :spring-lobby/reconcile-maps})

(def resource-tasks
  #{:spring-lobby/import
    :spring-lobby/map-details
    :spring-lobby/mod-details})

(def download-tasks
  #{:spring-lobby/download-and-extract
    :spring-lobby/download-springfiles
    :spring-lobby/extract-7z
    :spring-lobby/import
    :spring-lobby/http-downloadable
    :spring-lobby/rapid-downloadable
    :spring-lobby/update-rapid})

(def task-kinds
  [:spring-lobby/index-task :spring-lobby/resource-task :spring-lobby/download-task
   :spring-lobby/other-task])

(defn task-kind [{:spring-lobby/keys [task-type]}]
  (cond
    (contains? index-tasks task-type) :spring-lobby/index-task
    (contains? resource-tasks task-type) :spring-lobby/resource-task
    (contains? download-tasks task-type) :spring-lobby/download-task
    :else :spring-lobby/other-task))
