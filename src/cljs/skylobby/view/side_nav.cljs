(ns skylobby.view.side-nav
  (:require
    [skylobby.util :as u]))


(defn side-nav [_]
  [:div {:class "absolute top left"}
   [:div
    [:a
     {:href (u/href :skylobby/servers)}
     [:span.material-icons
      {:style {:font-size 48}}
      "web"]]]
   [:div
    [:a
     {:href (u/href :skylobby/replays)}
     [:span.material-icons
      {:style {:font-size 48}}
      "movie"]]]
   [:div
    [:a
     {:href (u/href :skylobby/settings)}
     [:span.material-icons
      {:style {:font-size 48}}
      "settings"]]]])
