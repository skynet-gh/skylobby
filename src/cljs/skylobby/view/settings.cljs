(ns skylobby.view.settings
  (:require
    [skylobby.view.side-nav :as side-nav]))


(defn settings-page [_]
  [:div {:class "flex"}
   [side-nav/side-nav]
   [:div {:class "flex-auto justify-center"}
    [:div {:class "flex justify-center mb2 f2"} 
     "Settings"]]])
