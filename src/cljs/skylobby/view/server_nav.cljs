(ns skylobby.view.server-nav
  (:require 
    [re-frame.core :as rf]
    [skylobby.css :as css]
    [skylobby.util :as u]))


(def server-route-names
  {:skylobby/battles "Battles"
   :skylobby/channels "Chat"})


(defn listen [query-v]
  @(rf/subscribe query-v))

(defn server-nav []
  (let [current-route (listen [:skylobby/current-route])
        {:keys [parameters]} current-route
        server-url (-> parameters :path :server-url)
        username (-> parameters :query :username)
        server-key (u/get-server-key server-url username)
        battle (listen [:skylobby/battle server-key])
        route-names (concat
                      (when battle
                        [:skylobby/room])
                      [:skylobby/battles :skylobby/channels])
        battle-title (str "Battle: " (listen [:skylobby/battle-title server-key]))]
    [:div {:class "flex justify-center"}
     (for [route-name route-names]
       ^{:key route-name}
       [:div {:class "pa3"}
        [:a
         {:class (str css/header-class " "
                   (if (or (= route-name (-> current-route :data :name))
                           (and (= :skylobby/chat route-name)
                                (= :skylobby/channels (-> current-route :data :name))))
                     "gold"
                     "gray"))
          :href (u/href route-name {:server-url server-url} {:username username})}
         (or (get server-route-names route-name)
             battle-title)]])]))
