(ns skylobby.view.battle
  (:require
    [clojure.string :as string]
    [re-frame.core :as rf]
    [skylobby.common.util :as cu]
    [skylobby.view.chat :as chat-view]
    [skylobby.view.server-nav :as server-nav]
    [skylobby.view.servers-nav :as servers-nav]
    [skylobby.util :as u]))


(def minimap-sizes
  [256 384 512])
(def default-minimap-size
  (first minimap-sizes))

(def minimap-types
  ["minimap" "metalmap" "heightmap"])
(def default-minimap-type "minimap")


(defn listen [query-v]
  @(rf/subscribe query-v))

(def title-class "light-gray")

(defn room-page [_]
  (let [current-route (listen [:skylobby/current-route])
        {:keys [parameters]} current-route
        server-key (u/params-server-key parameters)
        username (or (:username server-key)
                     (-> parameters :query :username))
        {:keys [scripttags] :as battle} (listen [:skylobby/battle server-key])
        battles (listen [:skylobby/battles server-key])
        battle-details (get battles (:battle-id battle))
        users (listen [:skylobby/users server-key])
        minimap-size (or (listen [:skylobby/minimap-size]) default-minimap-size)
        minimap-px (str minimap-size "px")
        minimap-type (or (listen [:skylobby/minimap-type]) default-minimap-type)
        {:keys [servers]} (listen [:skylobby/servers])
        spring-running (listen [:skylobby/spring-running])]
    [:div
     {:class "flex"
      :style {:flex-flow "column"
              :height "100%"}}
     [servers-nav/servers-nav]
     (when-not (cu/is-direct? server-key)
       [server-nav/server-nav])
     [:div
      {:class "flex justify-center"}
      [:button
       {:on-click (fn [] (rf/dispatch [:skylobby/leave-battle server-key]))}
       "Leave Battle"]]
     (let [
           map-name (:battle-map battle-details)
           users-parsed (->> battle
                             :users
                             (map
                               (fn [[username user]]
                                 (let [username-lc (string/lower-case username)
                                       {:strs [skill skilluncertainty]} (get-in scripttags ["game" "players" username-lc])
                                       uncertainty (js/parseInt skilluncertainty)
                                       user-details (get users username)
                                       color (u/spring-color-to-web (:team-color user))]
                                   [username
                                    (assoc user
                                           :user-details user-details
                                           :color color
                                           :username-lc username-lc
                                           :skill skill
                                           :uncertainty uncertainty)])))
                             (sort-by (juxt (comp not :mode :battle-status second)
                                            (comp (fnil - 0) :bot :client-status :user second)
                                            (comp (fnil int 0) :ally :battle-status second)
                                            (comp (fnil int 0) :id :battle-status second)
                                            (comp (fnil int 0) :skill second)
                                            first)))]
       [:div
        {:class "flex justify-center"
         :style {:flex "0 1 auto"
                 :height minimap-px}}
        [:table
         {:class "flex"
          :style
          {:flex-grow 1
           :overflow-y "auto"
           :width "100%"
           :display "block"}}
         [:thead
          [:tr
           [:th {:class title-class} "Nickname"]
           [:th {:class title-class} "Skill"]
           [:th {:class title-class} "Status"]
           [:th {:class title-class} "Ally"]
           [:th {:class title-class} "Team"]
           [:th {:class title-class} "Color"]
           #_[:th {:class title-class} "Spectator"]
           [:th {:class title-class} "Faction"]
           [:th {:class title-class} "Rank"]
           [:th {:class title-class} "Country"]
           [:th {:class title-class} "Bonus"]]]
         [:tbody
          (for [[username user] users-parsed]
            (let [
                  {:keys [battle-status color user-details]} user
                  {:keys [client-status]} user-details
                  sync-status (int (or (:sync battle-status) 0))]
              ^{:key username}
              [:tr
               {:style {:white-space "nowrap"}}
               [:td
                {:class title-class
                 :style
                 (merge
                   {:width "90%"
                    :text-align "center"
                    :text-shadow "1px 1px #000000"
                    :font-weight (if (:mode battle-status) "bold" "normal")}
                   (when (:mode battle-status)
                     {:color color}))}
                username]
               (let [{:keys [skill uncertainty]} user]
                 [:td 
                  {:class title-class
                   :style
                   {:width "10%"}}
                  skill
                  " "
                  (when (number? uncertainty)
                    (apply str (repeat uncertainty "?")))])
               [:td
                {:class title-class}
                [:span.material-icons
                 (cond
                   (:bot client-status)
                   "smart_toy"
                   (not (:mode battle-status))
                   "search"
                   :else
                   "person")]
                [:span.material-icons
                 {:class
                  (case sync-status
                    1 "green"
                    2 "red"
                    "gold")}
                 (case sync-status
                   1 "sync"
                   2 "sync_disabled"
                   "sync_problem")]
                (when (:ingame client-status)
                  [:span.material-icons
                   {:class "red"}
                   "sports_esports"])
                (when (:away client-status)
                  [:span.material-icons
                   {:class "gray"}
                   "bed"])]
               [:td 
                {:class title-class}
                (:ally battle-status)]
               [:td 
                {:class title-class}
                (:id battle-status)]
               [:td 
                {:class title-class
                 :style {:background color}}]
               #_
               [:td 
                {:class title-class}
                (str (not (:mode battle-status)))]
               [:td 
                {:class title-class}
                (str (:side battle-status))]
               [:td 
                {:class title-class}
                (str (:rank client-status))]
               [:td 
                {:class title-class}
                (str (:country user-details))]
               [:td 
                {:class title-class}
                (str (:handicap battle-status))]]))]]
        [:div
         {:style {:min-width "300px"}}
         [:div
          {:class "ma1 ba br1 pa1"}
          (str (:battle-engine battle-details) " " (:battle-version battle-details))]
         [:div
          {:class "ma1 ba br1 pa1"}
          (str (:battle-modname battle-details))]
         [:div
          {:class "ma1 ba br1 pa1"}
          (str (:battle-map battle-details))]]
        [:div
         {:class "flex justify-center"
          :style {
                  :min-width minimap-px
                  :min-height minimap-px
                  :max-width minimap-px
                  :max-height minimap-px}}
         (when map-name
           [:img
            {:src (str "/minimap-image?map-name=" map-name
                       (when minimap-type
                         (str "&minimap-type=" minimap-type)))
             :alt (str map-name)
             :style {:max-width "100%"
                     :max-height "100%"
                     :object-fit "contain"}}])]])
     (let [my-battle-status (get-in battle [:users username :battle-status])
           {:keys [host-username]} battle-details
           my-client-status (get-in users [username :client-status])
           host-client-status (get-in users [host-username :client-status])]
       (println (keys battle))
       (println host-username)
       (println host-client-status)
       [:div {:class "flex justify-center"}
        [:div {:class "flex items-center mb2 mh2"}
         [:input
          {:class "mr2"
           :type "checkbox"
           :on-change #(rf/dispatch [:skylobby/set-auto-launch server-key (-> % .-target .-checked)])
           :checked (listen [:skylobby/auto-launch server-key])}]
         [:label {:class "lh-copy"} " Auto launch "]]
        [:select
         {:class "mv2 mh2 ph1 pv1"
          :on-change #(rf/dispatch [:skylobby/set-away server-key (= "true" (-> % .-target .-value))])
          :value (boolean (get-in users [username :client-status :away]))}
         [:option
          {:value false}
          "Here"]
         [:option
          {:value true}
          "Away"]]
        [:select
         {:class "mv2 mh2 ph1 pv1"
          :on-change #(rf/dispatch [:skylobby/set-battle-mode server-key (= "true" (-> % .-target .-value))])
          :value (boolean (:mode my-battle-status))}
         [:option
          {:value true}
          "Playing"]
         [:option
          {:value false}
          "Spectating"]]
        [:div {:class "flex items-center mb2 mh2"}
         [:input
          {:class "mr2"
           :type "checkbox"
           :on-change #(rf/dispatch [:skylobby/set-auto-unspec server-key (-> % .-target .-checked)])
           :checked (listen [:skylobby/auto-unspec server-key])}]
         [:label {:class "lh-copy"} " Auto unspec "]]
        (when (:mode my-battle-status)
          [:div {:class "flex items-center mb2 mh2"}
           [:input
            {:class "mr2"
             :on-change #(rf/dispatch [:skylobby/set-battle-ready server-key (-> % .-target .-checked)])
             :type "checkbox"
             :checked (boolean (:raedy my-battle-status))}]
           [:label {:class "lh-copy"} " Ready "]])
        [:span {:style {:flex-grow 1}}]
        [:button
         {:class "f3 mh4 mv2 pointer"
          :on-click #(rf/dispatch [:skylobby/start-battle server-key])
          :disabled (boolean
                      (or (:ingame my-client-status)
                          (and (not (:ingame host-client-status))
                               (not (:mode my-battle-status)))))}
         (if (:ingame my-client-status)
           "Game Running"
           (if (:ingame host-client-status)
             "Join Game"
             (if (:mode my-battle-status) ; playing
               "Start Game"
               "Game not running")))]
        [:span {:style {:flex-grow 1}}]
        [:div {:class "flex items-center mb2 mh2"}
         [:label " Minimap type: "]
         [:select
          {:class "mv2 mh2 ph1 pv1"
           :on-change #(rf/dispatch [:skylobby/assoc :minimap-type (-> % .-target .-value)])
           :value minimap-type}
          (for [minimap-type minimap-types]
            ^{:key minimap-type}
            [:option (str minimap-type)])]]
        [:div {:class "flex items-center mb2 mh2"}
         [:label " Minimap size: "]
         [:select
          {:class "mv2 mh2 ph1 pv1"
           :on-change #(rf/dispatch [:skylobby/assoc :minimap-size (-> % .-target .-value js/parseInt)])
           :value minimap-size}
          (for [size minimap-sizes]
            ^{:key size}
            [:option (str size)])]]])
     [:div
      {:class "flex flex-column"
       :style {
               :flex "1 1 auto"
               :flex-flow "column"
               :overflow-y "auto"}}
      ;[chat-view {:channel-name (u/battle-channel-name battle) :server-key server-key}]
      [chat-view/auto-scroll-chat-history {:channel-name (u/battle-channel-name battle) :server-key server-key}]]
     [:div {:class "flex flex-column"}
      [chat-view/chat-input {:channel-name (u/battle-channel-name battle) :server-key server-key}]]]))
