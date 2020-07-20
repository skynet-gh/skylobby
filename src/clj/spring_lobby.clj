(ns spring-lobby
  (:require
    [cljfx.api :as fx]
    [spring-lobby.client :as client]
    [taoensso.timbre :refer [info trace warn]]))


(defonce *state
  (atom {}))

(defmulti event-handler :event/type)

(defn battle-opts []
  {:mod-hash -1706632985
   :engine-version "104.0.1-1510-g89bb8e3 maintenance"
   :map-name "Dworld Acidic"
   :title "deth"
   :mod-name "Balanced Annihilation V9.79.4"
   :map-hash -1611391257})

(defn menu-view [opts]
  {:fx/type :menu-bar
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
              :text "menu4 item2"}]}]})

(defn battles-table [{:keys [battles]}]
  {:fx/type :table-view
   :items (vec (vals battles))
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
      :describe (fn [i] {:text (str (:battle-engine i) " " (:battle-version i))})}}]})

(defn user-table [{:keys [users]}]
  {:fx/type :table-view
   :items (vec (vals users))
   :columns
   [{:fx/type :table-column
     :text "Status"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe (fn [i] {:text (str (:client-status i))})}}
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
      :describe (fn [i] {:text (str (:user-agent i))})}}]})

(defn client-buttons [{:keys [client]}]
  {:fx/type :h-box
   :alignment :top-left
   :children
   (concat
     [{:fx/type :button
       :text (if client "Disconnect" "Connect")
       :on-action (fn [_]
                    (if client
                      (do
                        (client/disconnect client)
                        (swap! *state dissoc :client :users :battles))
                      (swap! *state assoc :client (client/connect *state))))}]
     (when client
       [{:fx/type :button
         :text "Host Battle"
         :on-action (fn [_]
                      (client/open-battle client (battle-opts)))}]))})

(defn battle-table [{:keys [battle battles users]}]
  (let [battle-users (:users battle)
        items (mapv (fn [[k v]] (assoc v :username k :user (get users k))) battle-users)]
    {:fx/type :table-view
     :items items
     :columns
     [{:fx/type :table-column
       :text "Country"
       :cell-value-factory identity
       :cell-factory
       {:fx/cell-type :table-cell
        :describe (fn [i] {:text (str (:country (:user i)))})}}
      {:fx/type :table-column
       :text "Status"
       :cell-value-factory identity
       :cell-factory
       {:fx/cell-type :table-cell
        :describe (fn [i] {:text (str (select-keys (:client-status (:user i)) [:bot :access]))})}}
      {:fx/type :table-column
       :text "Ingame"
       :cell-value-factory identity
       :cell-factory
       {:fx/cell-type :table-cell
        :describe (fn [i] {:text (str (:ingame (:client-status (:user i))))})}}
      {:fx/type :table-column
       :text "Faction"
       :cell-value-factory identity
       :cell-factory
       {:fx/cell-type :table-cell
        :describe (fn [i] {:text (str (:side (:battle-status i)))})}}
      {:fx/type :table-column
       :text "Rank"
       :cell-value-factory identity
       :cell-factory
       {:fx/cell-type :table-cell
        :describe (fn [i] {:text (str (:rank (:client-status (:user i))))})}}
      {:fx/type :table-column
       :text "TrueSkill"
       :cell-value-factory identity
       :cell-factory
       {:fx/cell-type :table-cell
        :describe (fn [i] {:text ""})}}
      {:fx/type :table-column
       :text "Color"
       :cell-value-factory identity
       :cell-factory
       {:fx/cell-type :table-cell
        :describe (fn [i] {:text (str (:team-color i))})}}
      {:fx/type :table-column
       :text "Nickname"
       :cell-value-factory identity
       :cell-factory
       {:fx/cell-type :table-cell
        :describe (fn [i] {:text (str (:username i))})}}
      {:fx/type :table-column
       :text "Player ID"
       :cell-value-factory identity
       :cell-factory
       {:fx/cell-type :table-cell
        :describe (fn [i] {:text (str (:id (:battle-status i)))})}}
      {:fx/type :table-column
       :text "Team ID"
       :cell-value-factory identity
       :cell-factory
       {:fx/cell-type :table-cell
        :describe (fn [i] {:text (str (:ally (:battle-status i)))})}}
      {:fx/type :table-column
       :text "Bonus"
       :cell-value-factory identity
       :cell-factory
       {:fx/cell-type :table-cell
        :describe (fn [i] {:text (str (:handicap (:battle-status i)) "%")})}}]}))


(defn root-view [{{:keys [client users battles battle]} :state}]
  {:fx/type :stage
   :showing true
   :title "Alt Spring Lobby"
   :width 900
   :height 700
   :scene {:fx/type :scene
           :root {:fx/type :v-box
                  :alignment :top-left
                  :children [{:fx/type menu-view}
                             {:fx/type battles-table
                              :battles battles}
                             {:fx/type user-table
                              :users users}
                             {:fx/type battle-table
                              :battles battles
                              :battle battle
                              :users users}
                             {:fx/type client-buttons
                              :client client}]}}})

(defn mount-renderer [& args]
  (let [r (fx/create-renderer
            :middleware (fx/wrap-map-desc (fn [state]
                                            {:fx/type root-view
                                             :state state}))
            :opts {:fx.opt/map-event-handler event-handler})]
    (fx/mount-renderer *state r)
    r))


#_
(-> *state deref)
#_
(let [c (-> *state deref :client)]
  (client/open-battle c {:mod-hash -1706632985
                         :engine-version "104.0.1-1510-g89bb8e3 maintenance"
                         :map-name "Dworld Acidic"
                         :title "deth"
                         :mod-name "Balanced Annihilation V9.79.4"
                         :map-hash -1611391257}))
