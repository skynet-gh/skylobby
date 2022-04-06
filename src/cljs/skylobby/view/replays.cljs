(ns skylobby.view.replays
  (:require
    [re-frame.core :as rf]
    [skylobby.view.side-nav :as side-nav]))


(defn listen [query-v]
  @(rf/subscribe query-v))


(def title-class "light-gray")

(defn replays-page [_]
  (let [parsed-replays-by-path (listen [:skylobby/replays])
        replays-watched (listen [:skylobby/replays-watched])
        spring-running (listen [:skylobby/spring-running])
        watching-a-replay (get-in spring-running [:replay :replay])
        {:keys [engines maps mods]} (listen [:skylobby/global-spring-resources])
        engines-by-version (->> engines
                                (map (juxt :engine-version identity))
                                (into {}))
        mods-by-name (->> mods
                          (map (juxt :mod-name identity))
                          (into {}))
        maps-by-name (->> maps
                          (map (juxt :map-name identity))
                          (into {}))]
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
          {:class title-class}
          "Watched?"]
         [:th
          {:class title-class
           :style {:width "99%"}}
          "Actions"]]]
       [:tbody
        (for [[path replay] parsed-replays-by-path]
          (let [{:keys [file replay-id replay-map-name replay-mod-name replay-engine-version]} replay
                has-resources (and (get engines-by-version replay-engine-version)
                                   (get mods-by-name replay-mod-name)
                                   (get maps-by-name replay-map-name))]
            ^{:key (str path)}
            [:tr
             {:class "hover-bg-near-black"
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
             [:td
              {:class title-class}
              (->> file
                   :path
                   (get replays-watched)
                   boolean
                   str)]
             [:td.justify-center
              {:class title-class}
              [:button
               {:class "pointer"
                :disabled (boolean
                            (or watching-a-replay
                                (not has-resources)))
                :on-click (fn [_event]
                            (rf/dispatch
                              [:skylobby/watch-replay
                               {:replay-file file}]))}
               (if (not has-resources)
                 "Missing resources"
                 (if watching-a-replay
                   "Watching a replay"
                   "Watch"))]]]))]]]]))
