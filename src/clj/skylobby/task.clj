(ns skylobby.task)


(def index-tasks
  #{:spring-lobby/reconcile-engines
    :spring-lobby/reconcile-mods
    :spring-lobby/reconcile-maps})

(def resource-tasks
  #{:spring-lobby/import
    :spring-lobby/http-downloadable
    :spring-lobby/rapid-downloadable
    :spring-lobby/update-rapid})

(def task-kinds [:spring-lobby/index-task :spring-lobby/resource-task :spring-lobby/other-task])

(defn task-kind [{:spring-lobby/keys [task-type]}]
  (cond
    (contains? index-tasks task-type) :spring-lobby/index-task
    (contains? resource-tasks task-type) :spring-lobby/resource-task
    :else :spring-lobby/other-task))
