(ns skylobby.fx.matchmaking
  (:require
    [cljfx.api :as fx]
    skylobby.fx
    [skylobby.util :as u]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(def matchmaking-window-width 600)
(def matchmaking-window-height 700)


(defn matchmaking-view
  [{:fx/keys [context]
    :keys [server-key]}]
  (let [client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        matchmaking-queues (fx/sub-val context get-in [:by-server server-key :matchmaking-queues])]
    {:fx/type :v-box
     :style {:-fx-font-size 16}
     :children
     [
      #_
      {:fx/type :button
       :text "List All Queues"
       :on-action {:event/type :spring-lobby/matchmaking-list-all
                   :client-data client-data}}
      #_
      {:fx/type :button
       :text "List My Queues"
       :on-action {:event/type :spring-lobby/matchmaking-list-my
                   :client-data client-data}}
      {:fx/type :button
       :text "Leave All Queues"
       :on-action {:event/type :spring-lobby/matchmaking-leave-all
                   :client-data client-data}}
      {:fx/type :label
       :text "Queues"
       :style {:-fx-font-size 24}}
      {:fx/type :table-view
       :v-box/vgrow :always
       :style {:-fx-font-size 16}
       :column-resize-policy :constrained ; TODO auto resize
       :items (or (sort-by first matchmaking-queues)
                  [])
       :columns
       [{:fx/type :table-column
         :text "Queue"
         :cell-value-factory (comp :queue-name second)
         :cell-factory
         {:fx/cell-type :table-cell
          :describe (fn [queue-name] {:text (str queue-name)})}}
        {:fx/type :table-column
         :text "Current Search Time"
         :cell-value-factory (comp :current-search-time second)
         :cell-factory
         {:fx/cell-type :table-cell
          :describe (fn [current-search-time] {:text (str current-search-time)})}}
        {:fx/type :table-column
         :text "Current Size"
         :cell-value-factory (comp :current-size second)
         :cell-factory
         {:fx/cell-type :table-cell
          :describe (fn [current-size] {:text (str current-size)})}}
        {:fx/type :table-column
         :text "Actions"
         :cell-value-factory identity
         :cell-factory
         {:fx/cell-type :table-cell
          :describe
          (fn [[queue-id {:keys [am-in queue-name ready-check]}]]
            {:text ""
             :graphic
             {:fx/type :h-box
              :children
              (concat
                [{:fx/type :button
                  :text (cond
                          ready-check "Ready"
                          am-in "Leave"
                          :else "Join")
                  :on-action
                  {:event/type (if ready-check
                                 :spring-lobby/matchmaking-ready
                                 (if am-in
                                   :spring-lobby/matchmaking-leave
                                   :spring-lobby/matchmaking-join))
                   :client-data client-data
                   :queue-id queue-id
                   :queue-name queue-name}}]
                (when ready-check
                  [{:fx/type :button
                    :text "Decline"
                    :on-action
                    {:event/type :spring-lobby/matchmaking-decline
                     :client-data client-data
                     :queue-id queue-id}}]))}})}}]}]}))


(defn matchmaking-window-impl
  [{:fx/keys [context]
    :keys [screen-bounds server-key]}]
  (let [show-matchmaking-window (fx/sub-val context :show-matchmaking-window)]
    {:fx/type :stage
     :showing (boolean show-matchmaking-window)
     :title (str u/app-name " Matchmaking")
     :icons skylobby.fx/icons
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :show-matchmaking-window}
     :width ((fnil min matchmaking-window-width) (:width screen-bounds) matchmaking-window-width)
     :height ((fnil min matchmaking-window-height) (:height screen-bounds) matchmaking-window-height)
     :scene
     {:fx/type :scene
      :stylesheets (fx/sub-ctx context skylobby.fx/stylesheet-urls-sub)
      :root
      (if show-matchmaking-window
        {:fx/type matchmaking-view
         :server-key server-key}
        {:fx/type :pane
         :pref-width matchmaking-window-width
         :pref-height matchmaking-window-height})}}))

(defn matchmaking-window [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :matchmaking-window
      (matchmaking-window-impl state))))
