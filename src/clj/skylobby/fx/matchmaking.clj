(ns skylobby.fx.matchmaking
  (:require
    skylobby.fx
    [spring-lobby.util :as u]
    [taoensso.tufte :as tufte]))


(defn matchmaking-window [{:keys [client-data matchmaking-queues show-matchmaking-window]}]
  (tufte/profile {:id :skylobby/ui}
    (tufte/p :replays-window
      {:fx/type :stage
       :showing (boolean show-matchmaking-window)
       :title (str u/app-name " Matchmaking")
       :icons skylobby.fx/icons
       :on-close-request {:event/type :spring-lobby/dissoc
                          :key :show-matchmaking-window}
       :width 600
       :height 700
       :scene
       {:fx/type :scene
        :stylesheets skylobby.fx/stylesheets
        :root
        (if show-matchmaking-window
          {:fx/type :v-box
           :style {:-fx-font-size 16}
           :children
           [{:fx/type :button
             :text "List All Queues"
             :on-action {:event/type :spring-lobby/matchmaking-list-all
                         :client-data client-data}}
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
                           :queue-id queue-id}}]))}})}}]}]}
          {:fx/type :pane})}})))
