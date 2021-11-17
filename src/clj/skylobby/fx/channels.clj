(ns skylobby.fx.channels
  (:require
    [cljfx.api :as fx]
    skylobby.fx
    [skylobby.util :as u]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(defn- channels-table-impl
  [{:fx/keys [context]
    :keys [server-key]}]
  (let [
        channels (fx/sub-val context get-in [:by-server server-key :channels])
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        my-channels (fx/sub-val context get-in [:by-server server-key :my-channels])
        items (->> (vals channels)
                   u/non-battle-channels
                   (sort-by :channel-name String/CASE_INSENSITIVE_ORDER)
                   doall)]
    {:fx/type :table-view
     :style {:-fx-min-width "240"}
     :column-resize-policy :constrained
     :items items
     :columns
     [{:fx/type :table-column
       :text "Channel"
       :pref-width 100
       :cell-value-factory identity
       :cell-factory
       {:fx/cell-type :table-cell
        :describe (fn [i] {:text (str (:channel-name i))})}}
      {:fx/type :table-column
       :text "Users"
       :resizable false
       :pref-width 60
       :cell-value-factory identity
       :cell-factory
       {:fx/cell-type :table-cell
        :describe (fn [i] {:text (str (:user-count i))})}}
      {:fx/type :table-column
       :text "Actions"
       :resizable false
       :pref-width 80
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
                            :client-data client-data}}))})}}]}))

(defn channels-table [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :channels-table
      (channels-table-impl state))))
