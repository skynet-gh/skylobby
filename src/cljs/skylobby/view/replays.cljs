(ns skylobby.view.replays
  (:require
    [re-frame.core :as rf]
    [skylobby.view.side-nav :as side-nav]))


(defn listen [query-v]
  @(rf/subscribe query-v))


(def title-class "light-gray")

(defn replays-page [_]
  (let [parsed-replays-by-path (listen [:skylobby/replays])]
    [:div {:class "flex"}
     [side-nav/side-nav]
     [:div {:class "flex justify-center vh-100 w-100"}
      [:table
       {
        :class "flex-auto db overflow-y-scroll"
        :style
        {:flex-grow 1
         :width "100%"}}
       [:thead
        [:tr
         [:th 
          {:class title-class}
          "ID"]
         [:th 
          {:class title-class}
          "Map"]
         [:th 
          {:class title-class}
          "Game"]
         [:th 
          {:class title-class}
          "Engine"]
         [:th 
          {:class title-class
           :style {:width "99%"}}
          "Actions"]]]
       [:tbody
        (for [[path replay] parsed-replays-by-path]
          (let [{:keys [replay-id replay-map-name replay-mod-name replay-engine-version]} replay]
            ^{:key (str path)}
            [:tr
             {:class "hover-near-black"
              :style {:white-space "nowrap"}}
             [:td 
              {:class title-class}
              replay-id]
             [:td 
              {:class title-class}
              replay-map-name]
             [:td 
              {:class title-class}
              replay-mod-name]
             [:td 
              {:class title-class}
              replay-engine-version]
             [:td.justify-center
              {:class title-class}
              [:button 
               "Watch"]]]))]]]]))
