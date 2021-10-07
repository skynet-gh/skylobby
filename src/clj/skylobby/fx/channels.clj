(ns skylobby.fx.channels
  (:require
    [cljfx.api :as fx]
    skylobby.fx
    [spring-lobby.util :as u]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(defn- channels-table-impl
  [{:fx/keys [context]
    :keys [server-key]}]
  (let [
        channels (fx/sub-val context get-in [:by-server server-key :channels])
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        my-channels (fx/sub-val context get-in [:by-server server-key :my-channels])]
    {:fx/type :table-view
     :column-resize-policy :constrained
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
                            :client-data client-data}}))})}}]}))

(defn channels-table [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :channels-table
      (channels-table-impl state))))
