(ns skylobby.fx.tasks
  (:require
    [cljfx.api :as fx]
    [clojure.pprint :refer [pprint]]
    skylobby.fx
    [skylobby.task :as task]
    [spring-lobby.util :as u]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(def tasks-window-width 1600)
(def tasks-window-height 1000)


(defn tasks-window-impl
  [{:fx/keys [context] :keys [screen-bounds]}]
  (let [show (boolean (fx/sub-val context :show-tasks-window))]
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
        {:fx/type :v-box
         :style {:-fx-font-size 16}
         :children
         [
          {:fx/type :h-box
           :style {:-fx-font-size 18}
           :children
           [
            {:fx/type :check-box
             :selected (boolean (fx/sub-val context :disable-tasks))
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :disable-tasks}}
            {:fx/type :label
             :text " Disable tasks"}]}
          {:fx/type :h-box
           :style {:-fx-font-size 18}
           :children
           [
            {:fx/type :check-box
             :selected (boolean (fx/sub-val context :disable-tasks-while-in-game))
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :disable-tasks-while-in-game}}
            {:fx/type :label
             :text " Disable tasks while in game"}]}
          {:fx/type :label
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
             (fx/sub-val context :current-tasks))}
          {:fx/type :label
           :text "Task Queue"
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
                  :text (str (with-out-str (pprint i)))}})}}]}]}
        {:fx/type :pane
         :pref-width tasks-window-width
         :pref-height tasks-window-height})}}))

(defn tasks-window [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :tasks-window
      (tasks-window-impl state))))
