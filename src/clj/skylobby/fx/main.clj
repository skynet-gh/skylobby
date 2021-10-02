(ns skylobby.fx.main
  (:require
    [cljfx.ext.tab-pane :as fx.ext.tab-pane]
    [skylobby.fx.server-tab :as fx.server-tab]
    [skylobby.fx.welcome :as fx.welcome]
    [skylobby.resource :as resource]
    [spring-lobby.util :as u]))


(set! *warn-on-reflection* true)


(defn main-window
  [{:keys [by-server by-spring-root selected-server-tab selected-tab-channel selected-tab-main
           server servers spring-isolation-dir username]
    :as state}]
  (let [valid-servers (u/valid-servers by-server)
        tab-ids (concat ["local"]
                        (map first valid-servers)
                        #_(when (seq valid-servers) ["multi"]))
        tab-id-set (set tab-ids)
        selected-index (or (when (contains? tab-id-set selected-server-tab)
                             (.indexOf ^java.util.List tab-ids selected-server-tab))
                           0)]
    {:fx/type :v-box
     :style {:-fx-font-size 14}
     :alignment :top-left
     :children
     [
      {:fx/type fx.ext.tab-pane/with-selection-props
       :v-box/vgrow :always
       :props
       (merge
         {:on-selected-item-changed {:event/type :spring-lobby/selected-item-changed-server-tabs}
          :selected-index selected-index})
       :desc
       {:fx/type :tab-pane
        :tabs
        (concat
          [{:fx/type :tab
            :id "local"
            :closable false
            :graphic {:fx/type :label
                      :text "local"
                      :style {:-fx-font-size 18}}
            :content
            (merge
              {:fx/type fx.welcome/welcome-view
               :v-box/vgrow :always}
              (select-keys state fx.welcome/welcome-view-keys)
              (-> by-server
                  (get (u/server-key {:server-url (first server)
                                      :username username}))
                  (select-keys fx.welcome/welcome-view-server-keys))
              {:selected-tab-channel selected-tab-channel
               :selected-tab-main selected-tab-main})}]
          (map
            (fn [[server-key server-data]]
              {:fx/type :tab
               :id (str server-key)
               :graphic {:fx/type :label
                         :text (str (-> server-data :server second :alias) " (" server-key ")")
                         :style {:-fx-font-size 18}}
               :on-close-request {:event/type :spring-lobby/disconnect
                                  :server-key server-key}
               :content
               (if (= (str server-key) selected-server-tab)
                 (merge
                   {:fx/type fx.server-tab/server-tab}
                   (select-keys state fx.server-tab/server-tab-state-keys)
                   server-data
                   {:selected-tab-channel selected-tab-channel
                    :selected-tab-main selected-tab-main
                    :server-key server-key}
                   (let [server-url (-> server-data :client-data :server-url)
                         spring-root (or (-> servers (get server-url) :spring-isolation-dir)
                                         spring-isolation-dir)]
                     (resource/spring-root-resources spring-root by-spring-root)))
                 {:fx/type :pane})})
            valid-servers)
          #_
          (when (seq valid-servers)
            [{:fx/type :tab
              :id "multi"
              :closable false
              :graphic {:fx/type :label
                        :text "All Servers"
                        :style {:-fx-font-size 18}}
              :content
              (merge
                {:fx/type multi-server-tab}
                (select-keys state [:map-details :mod-details]))}]))}}]}))
