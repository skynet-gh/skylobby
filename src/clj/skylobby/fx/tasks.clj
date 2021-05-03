(ns skylobby.fx.tasks
  (:require
    [clojure.pprint :refer [pprint]]
    skylobby.fx
    [skylobby.task :as task]
    [spring-lobby.util :as u]
    [taoensso.tufte :as tufte]))


(def tasks-window-width 1600)
(def tasks-window-height 1000)

(def tasks-window-keys
  [:css :current-tasks :show-tasks-window :tasks-by-kind])

(defn tasks-window-impl
  [{:keys [css current-tasks screen-bounds show-tasks-window tasks-by-kind]}]
  (let [{:keys [width height]} screen-bounds]
    {:fx/type :stage
     :showing (boolean show-tasks-window)
     :title (str u/app-name " Tasks")
     :icons skylobby.fx/icons
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :show-tasks-window}
     :width ((fnil min tasks-window-width) width tasks-window-width)
     :height ((fnil min tasks-window-height) height tasks-window-height)
     :scene
     {:fx/type :scene
      :stylesheets (skylobby.fx/stylesheet-urls css)
      :root
      (if show-tasks-window
        {:fx/type :v-box
         :style {:-fx-font-size 16}
         :children
         [{:fx/type :label
           :text "Workers"
           :style {:-fx-font-size 24}}
          {:fx/type :h-box
           :alignment :center-left
           :children
           (map
             (fn [[k v]]
               {:fx/type :v-box
                :children
                [{:fx/type :label
                  :style {:-fx-font-size 20}
                  :text (str " " k ": ")}
                 {:fx/type :text-area
                  :editable false
                  :wrap-text true
                  :text (str (with-out-str (pprint v)))}]})
             current-tasks)}
          {:fx/type :label
           :text "Task Queue"
           :style {:-fx-font-size 24}}
          {:fx/type :table-view
           :v-box/vgrow :always
           :column-resize-policy :constrained
           :items (or (->> tasks-by-kind
                           (mapcat second)
                           seq)
                      [])
           :columns
           [
            {:fx/type :table-column
             :text "Kind"
             :cell-value-factory task/task-kind
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [i]
                {:text (str i)})}}
            {:fx/type :table-column
             :text "Type"
             :cell-value-factory :spring-lobby/task-type
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [i]
                {:text (str i)})}}
            {:fx/type :table-column
             :text "Data"
             :cell-value-factory identity
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [i]
                {:text ""
                 :graphic
                 {:fx/type :text-area
                  :editable false
                  :wrap-text true
                  :text (str (with-out-str (pprint i)))}})}}]}]}
        {:fx/type :pane})}}))

(defn tasks-window [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :tasks-window
      (tasks-window-impl state))))
