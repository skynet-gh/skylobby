(ns skylobby.view.side-nav
  (:require
    [re-frame.core :as rf]
    [skylobby.util :as u]))


(defn listen [query-v]
  @(rf/subscribe query-v))


(def navs
  [{:route :skylobby/servers
    :icon "web"}
   {:route :skylobby/replays
    :icon "movie"}
   {:route :skylobby/settings
    :icon "settings"}
   {:route :skylobby/quit
    :icon "logout"}])

(defn side-nav [_]
  (let [current-route (listen [:skylobby/current-route])]
    [:div {:class "flex-column"}
     (for [{:keys [icon route]} navs]
       (let [color (if (= route (get-in current-route [:data :name]))
                     "red"
                     "gray")]
         ^{:key route}
         [:div.ma2
          [:div
           {:class (str "ba bw1 br2 ma2 b--" color " ")}
           [:a
            {
             :class color
             :href (u/href route)}
            [:span.material-icons
             {:style {:font-size 48}}
             icon]]]]))]))
