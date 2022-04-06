(ns skylobby.view.servers-nav
  (:require
    [re-frame.core :as rf]
    [skylobby.common.util :as cu]
    [skylobby.css :as css]
    [skylobby.util :as u]))


(defn listen [query-v]
  @(rf/subscribe query-v))

(defn servers-nav []
  (let [current-route (listen [:skylobby/current-route])
        {:keys [parameters]} current-route
        server-key (u/params-server-key parameters)]
    [:div
     [:div {:class "flex justify-center"}
      (let [route-name :skylobby/servers]
        ^{:key route-name}
        [:div 
         {:class "pa3"}
         [:a
          {:class (str css/header-class " "
                    (if (= route-name (-> current-route :data :name))
                      "purple"
                      "gray"))
           :href (u/href route-name)}
          "Servers"]])
      (for [{:keys [server-id server-url username]} (filter :server-id (listen [:skylobby/active-servers]))]
        (let [is-direct (cu/is-direct? server-id)]
          ^{:key (str server-id)}
          [:div
           {:class "pa3"}
           [:a
            {
             :class (str css/header-class " "
                      (if (= server-id server-key)
                        "purple"
                        "gray"))
             :href (if is-direct
                     (u/href :skylobby/direct-battle {:server-key server-id})
                     (u/href :skylobby/battles {:server-url server-url} {:username username}))}
            (if is-direct
              (let [{:keys [server-type username hostname port]} server-id]
                (str (name server-type) " " username "@" hostname ":" port))
              server-id)]]))]]))
