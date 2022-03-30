(ns skylobby.view.replays
  (:require
    [re-frame.core :as rf]
    [skylobby.view.side-nav :as side-nav]))


(defn listen [query-v]
  @(rf/subscribe query-v))


(def title-class "light-gray")

(defn replays-page [_]
  (let [parsed-replays-by-path (listen [:skylobby/replays])]
    [:div
     [side-nav/side-nav]
     [:div
      [:div {:class "flex justify-center mb2 f2"} 
       "Replays"]
      [:div {:class "flex justify-center"} 
       [:table
        {
         :style
         {:flex-grow 1}}
        [:thead
         [:tr
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
           {:class title-class}
           "Actions"]]]
        [:tbody
         (for [[path replay] parsed-replays-by-path]
           (let [{:keys [replay-id replay-map-name replay-mod-name replay-engine-version]} replay]
             ^{:key replay-id}
             [:tr
              [:td 
               {:class title-class}
               replay-map-name]
              [:td 
               {:class title-class}
               replay-mod-name]
              [:td 
               {:class title-class}
               replay-engine-version]
              [:td 
               {:class title-class}
               [:button 
                "Watch"]]]))]]]]]))
