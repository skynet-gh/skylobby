(ns spring-lobby
  (:require
    [cljfx.api :as fx]
    [spring-lobby.client :as client]))


(defonce *state
  (atom {}))

(defmulti event-handler :event/type)


(defn root-view [{{:keys [client users battles]} :state}]
  {:fx/type :stage
   :showing true
   :title "Alt Spring Lobby"
   :width 900
   :height 700
   :scene {:fx/type :scene
           :root {:fx/type :v-box
                  :alignment :top-left
                  :children [{:fx/type :menu-bar
                              :menus
                              [{:fx/type :menu
                                :text "Server"
                                :items [{:fx/type :menu-item
                                         :text "Connect"}
                                        {:fx/type :menu-item
                                         :text "Disconnect"}
                                        {:fx/type :menu-item
                                         :text "Pick Server"}]}
                               {:fx/type :menu
                                :text "Edit"
                                :items [{:fx/type :menu-item
                                         :text "menu2 item1"}
                                        {:fx/type :menu-item
                                         :text "menu2 item2"}]}
                               {:fx/type :menu
                                :text "Tools"
                                :items [{:fx/type :menu-item
                                         :text "menu3 item1"}
                                        {:fx/type :menu-item
                                         :text "menu3 item2"}]}
                               {:fx/type :menu
                                :text "Help"
                                :items [{:fx/type :menu-item
                                         :text "menu4 item1"}
                                        {:fx/type :menu-item
                                         :text "menu4 item2"}]}]}
                             {:fx/type :table-view
                              :columns
                              [{:fx/type :table-column
                                :text "Status"}
                               {:fx/type :table-column
                                :text "Country"
                                :cell-value-factory identity
                                :cell-factory
                                {:fx/cell-type :table-cell
                                 :describe (fn [i] {:text (str (:country i))})}}
                               {:fx/type :table-column
                                :text " "}
                               {:fx/type :table-column
                                :text "Players"}
                               {:fx/type :table-column
                                :text "Max"}
                               {:fx/type :table-column
                                :text "Spectators"}
                               {:fx/type :table-column
                                :text "Running"}
                               {:fx/type :table-column
                                :text "Battle Name"
                                :cell-value-factory identity
                                :cell-factory
                                {:fx/cell-type :table-cell
                                 :describe (fn [i] {:text (str (:battle-title i))})}}
                               {:fx/type :table-column
                                :text "Game"
                                :cell-value-factory identity
                                :cell-factory
                                {:fx/cell-type :table-cell
                                 :describe (fn [i] {:text (str (:battle-modname i))})}}
                               {:fx/type :table-column
                                :text "Map"
                                :cell-value-factory identity
                                :cell-factory
                                {:fx/cell-type :table-cell
                                 :describe (fn [i] {:text (str (:battle-map i))})}}
                               {:fx/type :table-column
                                :text "Host"
                                :cell-value-factory identity
                                :cell-factory
                                {:fx/cell-type :table-cell
                                 :describe (fn [i] {:text (str (:host-username i))})}}
                               {:fx/type :table-column
                                :text "Engine"
                                :cell-value-factory identity
                                :cell-factory
                                {:fx/cell-type :table-cell
                                 :describe (fn [i] {:text (str (:battle-engine i) " " (:battle-version i))})}}]
                              :items (vec (vals battles))}
                             {:fx/type :table-view
                              :columns
                              [{:fx/type :table-column
                                :text "Status"
                                :cell-value-factory identity
                                :cell-factory
                                {:fx/cell-type :table-cell
                                 :describe (fn [i] {:text ""})}}
                               {:fx/type :table-column
                                :text "Country"
                                :cell-value-factory identity
                                :cell-factory
                                {:fx/cell-type :table-cell
                                 :describe (fn [i] {:text (str (:country i))})}}
                               {:fx/type :table-column
                                :text "Rank"
                                :cell-value-factory identity
                                :cell-factory
                                {:fx/cell-type :table-cell
                                 :describe (fn [i] {:text ""})}}
                               {:fx/type :table-column
                                :text "Username"
                                :cell-value-factory identity
                                :cell-factory
                                {:fx/cell-type :table-cell
                                 :describe (fn [i] {:text (str (:username i))})}}
                               {:fx/type :table-column
                                :text "Lobby Client"
                                :cell-value-factory identity
                                :cell-factory
                                {:fx/cell-type :table-cell
                                 :describe (fn [i] {:text (str (:user-agent i))})}}]
                              :items (vec (vals users))}
                             (if client
                               {:fx/type :button
                                :text "Disconnect"
                                :on-action (fn [_]
                                             (client/disconnect client)
                                             (swap! *state dissoc :client :users :battles))}
                               {:fx/type :button
                                :text "Connect"
                                :on-action (fn [_]
                                             (swap! *state assoc :client (client/connect *state)))})]}}})

(defn start []
  (let [r (fx/create-renderer
            :middleware (fx/wrap-map-desc (fn [state]
                                            {:fx/type root-view
                                             :state state}))
            :opts {:fx.opt/map-event-handler event-handler})]
    (fx/mount-renderer *state r)
    (defonce renderer r)))

#_
(start)
#_
(renderer)
