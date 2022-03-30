(ns skylobby.view.settings
  (:require
    [skylobby.view.side-nav :as side-nav]))


(defn settings-page [_]
  [:div 
   [side-nav/side-nav]
   [:div {:class "flex justify-center mb2 f2"} 
    "Settings"]])
