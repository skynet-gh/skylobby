(ns skylobby.view.battles
  (:require 
    ["moment" :as moment]
    [re-frame.core :as rf]
    [skylobby.data :as data]
    [skylobby.view.server-nav :as server-nav]
    [skylobby.view.servers-nav :as servers-nav]
    [skylobby.util :as u]))


(defn listen [query-v]
  @(rf/subscribe query-v))


(def title-class "light-gray")

(defn battles-page [_]
  (let [current-route (listen [:skylobby/current-route])
        {:keys [parameters]} current-route
        server-url (-> parameters :path :server-url)
        username (-> parameters :query :username)
        server-key (u/get-server-key server-url username)
        current-battle (listen [:skylobby/battle server-key])
        filter-battles (listen [:skylobby/filter-battles])
        hide-empty-battles (listen [:skylobby/hide-empty-battles])
        hide-locked-battles (listen [:skylobby/hide-locked-battles])
        hide-passworded-battles (listen [:skylobby/hide-passworded-battles])
        battles (data/filter-battles
                  (listen [:skylobby/battles server-key])
                  {:filter-battles filter-battles
                   :hide-empty-battles hide-empty-battles
                   :hide-locked-battles hide-locked-battles
                   :hide-passworded-battles hide-passworded-battles})
        users (listen [:skylobby/users server-key])]
    [:div
     [servers-nav/servers-nav]
     [server-nav/server-nav]
     [:div
      {:class "flex justify-center"}
      [:label {:class "mr2 lh-copy"} " Filter: "]
      [:input
       {:class "input-reset ba br1 b--black-20 mb2 mr2 db"
        :auto-focus true
        :autoComplete "off"
        :on-change #(rf/dispatch [:skylobby/assoc :skylobby/filter-battles (-> % .-target .-value)])
        :type "text"
        :value filter-battles}]
      [:input
       {:class "mr2 mb2"
        :type "checkbox"
        :on-change #(rf/dispatch [:skylobby/assoc :skylobby/hide-locked-battles (-> % .-target .-checked)])
        :checked hide-locked-battles}]
      [:label {:class "mr2 lh-copy"} " Hide locked "]
      [:input
       {:class "mr2 mb2"
        :type "checkbox"
        :on-change #(rf/dispatch [:skylobby/assoc :skylobby/hide-passworded-battles (-> % .-target .-checked)])
        :checked hide-passworded-battles}]
      [:label {:class "mr2 lh-copy"} " Hide passworded "]
      [:input
       {:class "mr2 mb2"
        :type "checkbox"
        :on-change #(rf/dispatch [:skylobby/assoc :skylobby/hide-empty-battles (-> % .-target .-checked)])
        :checked hide-empty-battles}]
      [:label {:class "mr2 lh-copy"} " Hide empty "]]
     [:div {:class "flex justify-center"}
      [:table
       {:style {:flex-grow 1}}
       [:thead
        [:tr
         [:th 
          {:class title-class}
          "Actions"]
         [:th 
          {:class title-class}
          "ID"]
         [:th 
          {:class title-class}
          "Title"]
         [:th 
          {:class title-class}
          "Status"]
         [:th 
          {:class title-class}
          "Map"]
         [:th 
          {:class title-class}
          "Play (Spec)"]
         [:th 
          {:class title-class}
          "Game"]
         [:th 
          {:class title-class}
          "Engine"]
         [:th 
          {:class title-class}
          "Owner"]]]
       [:tbody
        (for [battle battles]
          (let [{:keys [battle-id host-username]} battle
                {:keys [game-start-time] :as user-data} (get users host-username)
                ingame (-> user-data :client-status :ingame)]
            ^{:key battle-id}
            [:tr
             (let [in-battle (= battle-id (:battle-id current-battle))]
               [:td
                {:class title-class}
                [:button
                 {:class "f6 link dim ph3 pv1 mb2 dib white bg-near-black br2"
                  :on-click #(rf/dispatch
                               (if in-battle
                                 [:skylobby/leave-battle server-key]
                                 [:skylobby/join-battle server-key battle-id]))}
                 (if in-battle
                   "Leave"
                   "Join")]])
             [:td 
              {:class title-class}
              battle-id]
             [:td 
              {:class title-class}
              (:battle-title battle)]
             [:td
              {:class "flex ib items-center light-gray"}
              (if (or (= "1" (:battle-locked battle))
                      (= "1" (:battle-passworded battle)))
                [:span.material-icons 
                 {:class "gold"}
                 "lock"]
                [:span
                 {:style
                  {
                   :width "24px"
                   :height "24px"}}])
              (if ingame
                [:span.material-icons
                 {:class "red"}
                 "radio_button_checked"]
                [:span.material-icons
                 {:class "green"}
                 "radio_button_unchecked"])
              (when (and ingame game-start-time)
                (let [diff (- (.now js/Date) game-start-time)
                      duration (moment/duration diff "milliseconds")]
                  [:span.ml1 (str (u/format-hours (moment/utc (.asMilliseconds duration))))]))]
             [:td 
              {:class title-class}
              (:battle-map battle)]
             [:td 
              {:class title-class}
              (let [total-user-count (count (:users battle))
                    spec-count (:battle-spectators battle)]
                (str (- total-user-count spec-count)
                     " (" spec-count ")"))]
             [:td 
              {:class title-class}
              (:battle-modname battle)]
             [:td 
              {:class title-class}
              (str (:battle-engine battle) " " (:battle-version battle))]
             [:td 
              {:class title-class}
              (:host-username battle)]]))]]]]))
