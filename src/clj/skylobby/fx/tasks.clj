(ns skylobby.fx.tasks
  (:require
    [cljfx.api :as fx]
    [clojure.pprint :refer [pprint]]
    skylobby.fx
    [skylobby.fx.sync :refer [error-severity]]
    [skylobby.task :as task]
    [skylobby.util :as u]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(def tasks-window-width 1600)
(def tasks-window-height 1000)


(defn tasks-root
  [{:fx/keys [context]}]
  (let [task-threads (fx/sub-val context :task-threads)]
    {:fx/type :v-box
     :style {:-fx-font-size 16}
     :children
     [
      {:fx/type :h-box
       :style {:-fx-font-size 18
               :-fx-padding 4}
       :children
       [
        {:fx/type :check-box
         :selected (boolean (fx/sub-val context :disable-tasks))
         :on-selected-changed {:event/type :spring-lobby/assoc
                               :key :disable-tasks}}
        {:fx/type :label
         :text " Disable tasks"}]}
      {:fx/type :h-box
       :style {:-fx-font-size 18
               :-fx-padding 4}
       :children
       [
        {:fx/type :check-box
         :selected (boolean (fx/sub-val context :disable-tasks-while-in-game))
         :on-selected-changed {:event/type :spring-lobby/assoc
                               :key :disable-tasks-while-in-game}}
        {:fx/type :label
         :text " Disable tasks while in game"}]}
      {:fx/type :pane
       :pref-height 8}
      {:fx/type :label
       :text " Workers"
       :style {:-fx-font-size 24}}
      {:fx/type :h-box
       :alignment :center-left
       :children
       (map
         (fn [[k v]]
           {:fx/type :v-box
            :children
            (concat
              [{:fx/type :label
                :style {:-fx-font-size 20}
                :text (str "  " k)}
               {:fx/type :text-area
                :v-box/vgrow :always
                :editable false
                :wrap-text true
                :text (if v
                        (str (with-out-str (pprint v)))
                        "")}]
              (when-let [task-thread (get task-threads k)]
                (when v
                  [{:fx/type :button
                    :text "Stop"
                    :style (dissoc error-severity :-fx-background)
                    :on-action {:event/type :spring-lobby/stop-task
                                :task-kind k
                                :task-thread task-thread}}])))})
         (fx/sub-val context :current-tasks))}
      {:fx/type :pane
       :pref-height 8}
      {:fx/type :label
       :text " Task Queue"
       :style {:-fx-font-size 24}}
      {:fx/type :table-view
       :v-box/vgrow :always
       :column-resize-policy :constrained
       :items (or (->> (fx/sub-val context :tasks-by-kind)
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
              :text (str (with-out-str (pprint i)))}})}}
        {:fx/type :table-column
         :text "Actions"
         :sortable false
         :cell-value-factory identity
         :cell-factory
         {:fx/cell-type :table-cell
          :describe
          (fn [task]
            {:text ""
             :graphic
             {:fx/type :button
              :text "Cancel"
              :on-action {:event/type :spring-lobby/cancel-task
                          :task task}}})}}]}]}))


(defn tasks-window-impl
  [{:fx/keys [context] :keys [screen-bounds]}]
  (let [show (boolean
               (and
                 (fx/sub-val context :show-tasks-window)
                 (not (fx/sub-val context :windows-as-tabs))))]
    {:fx/type :stage
     :showing show
     :title (str u/app-name " Tasks")
     :icons skylobby.fx/icons
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :show-tasks-window}
     :x (skylobby.fx/fitx screen-bounds)
     :y (skylobby.fx/fity screen-bounds)
     :width (skylobby.fx/fitwidth screen-bounds tasks-window-width)
     :height (skylobby.fx/fitheight screen-bounds tasks-window-height)
     :scene
     {:fx/type :scene
      :stylesheets (fx/sub-ctx context skylobby.fx/stylesheet-urls-sub)
      :root
      (if show
        {:fx/type tasks-root}
        {:fx/type :pane
         :pref-width tasks-window-width
         :pref-height tasks-window-height})}}))

(defn tasks-window [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :tasks-window
      (tasks-window-impl state))))
