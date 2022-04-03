(ns skylobby.view.tasks
  (:require
    [re-frame.core :as rf]
    [skylobby.view.side-nav :as side-nav]))


(defn listen [query-v]
  @(rf/subscribe query-v))

(defn tasks-page [_]
  (let [{:keys [current-tasks tasks-by-kind]} (listen [:skylobby/tasks])]
    [:div {:class "flex"}
     [side-nav/side-nav]
     [:div {:class "flex-auto justify-center"}
      [:div {:class "flex justify-center mb2 f2"}
       "Tasks"]
      [:div 
       "Current: "
       (str (count current-tasks))]
      [:div 
       "Queued: "
       (str (count (mapcat second tasks-by-kind)))]
      [:div 
       "Types: "
       (str (keys tasks-by-kind))]]]))
