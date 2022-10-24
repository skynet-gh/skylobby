(ns skylobby.fx.channels
  (:require
    [cljfx.api :as fx]
    skylobby.fx
    [skylobby.util :as u]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(defn sorted-channels-sub [context server-key]
  (let [
        channels (fx/sub-val context get-in [:by-server server-key :channels])]
   (->> (vals channels)
        u/non-battle-channels
        (sort-by :channel-name String/CASE_INSENSITIVE_ORDER)
        doall)))

(defn- channels-table-impl
  [{:fx/keys [context]
    :keys [server-key]}]
  (let [
        my-channels (fx/sub-val context get-in [:by-server server-key :my-channels])
        sorted-channels (fx/sub-ctx context sorted-channels-sub server-key)]
    {:fx/type :table-view
     :style {:-fx-min-width "240"}
     :column-resize-policy :constrained
     :items sorted-channels
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
                            :server-key server-key}}
               {:text "Join"
                :on-action {:event/type :spring-lobby/join-channel
                            :channel-name channel-name
                            :server-key server-key}}))})}}]}))

(defn channels-table [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :channels-table
      (channels-table-impl state))))
