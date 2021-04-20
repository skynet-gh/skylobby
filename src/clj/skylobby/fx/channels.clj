(ns skylobby.fx.channels
  (:require
    [spring-lobby.util :as u]))


(def channels-table-keys
  [:channels :client-data :my-channels])

(defn channels-table [{:keys [channels client-data my-channels]}]
  {:fx/type :table-view
   :column-resize-policy :constrained ; TODO auto resize
   :items (->> (vals channels)
               u/non-battle-channels
               (sort-by :channel-name String/CASE_INSENSITIVE_ORDER))
   :columns
   [{:fx/type :table-column
     :text "Channel"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe (fn [i] {:text (str (:channel-name i))})}}
    {:fx/type :table-column
     :text "User Count"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe (fn [i] {:text (str (:user-count i))})}}
    {:fx/type :table-column
     :text "Actions"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe
      (fn [{:keys [channel-name]}]
        {:text ""
         :graphic
         (merge
           {:fx/type :button}
           (if (contains? my-channels channel-name)
             {:text "Leave"
              :on-action {:event/type :spring-lobby/leave-channel
                          :channel-name channel-name
                          :client-data client-data}}
             {:text "Join"
              :on-action {:event/type :spring-lobby/join-channel
                          :channel-name channel-name
                          :client-data client-data}}))})}}]})
