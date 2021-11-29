(ns skylobby.task)


(set! *warn-on-reflection* true)


(def index-tasks
  #{:spring-lobby/refresh-engines
    :spring-lobby/refresh-mods
    :spring-lobby/refresh-maps})

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
    :spring-lobby/rapid-download
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
