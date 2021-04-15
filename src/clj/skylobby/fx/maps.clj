(ns skylobby.fx.maps
  (:require
    [cljfx.ext.node :as fx.ext.node]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [spring-lobby.fx.font-icon :as font-icon]))


(defn maps-view
  [{:keys [disable map-name maps on-value-changed map-input-prefix spring-isolation-dir]}]
  {:fx/type :h-box
   :alignment :center-left
   :children
   (concat
     [{:fx/type :label
       :alignment :center-left
       :text " Map: "}]
     (if (empty? maps)
       [{:fx/type :label
         :text "No maps "}]
       (let [filter-lc (if map-input-prefix (string/lower-case map-input-prefix) "")
             filtered-map-names
             (->> maps
                  (map :map-name)
                  (filter #(string/includes? (string/lower-case %) filter-lc))
                  (sort String/CASE_INSENSITIVE_ORDER))]
         [{:fx/type :combo-box
           :prompt-text " < pick a map > "
           :value map-name
           :items filtered-map-names
           :disable (boolean disable)
           :on-value-changed on-value-changed
           :cell-factory
           {:fx/cell-type :list-cell
            :describe
            (fn [map-name]
              {:text (if (string/blank? map-name)
                       "< choose a map >"
                       map-name)})}
           :on-key-pressed {:event/type :spring-lobby/maps-key-pressed}
           :on-hidden {:event/type :spring-lobby/maps-hidden}}]))
     [{:fx/type :button
       :text ""
       :on-action {:event/type :spring-lobby/show-maps-window
                   :on-change-map on-value-changed}
       :graphic
       {:fx/type font-icon/lifecycle
        :icon-literal "mdi-magnify:16:white"}}
      {:fx/type fx.ext.node/with-tooltip-props
       :props
       {:tooltip
        {:fx/type :tooltip
         :show-delay [10 :ms]
         :text "Open maps directory"}}
       :desc
       {:fx/type :button
        :on-action {:event/type :spring-lobby/desktop-browse-dir
                    :file (io/file spring-isolation-dir "maps")}
        :graphic
        {:fx/type font-icon/lifecycle
         :icon-literal "mdi-folder:16:white"}}}]
     (when (seq maps)
       [{:fx/type fx.ext.node/with-tooltip-props
         :props
         {:tooltip
          {:fx/type :tooltip
           :show-delay [10 :ms]
           :text "Random map"}}
         :desc
         {:fx/type :button
          :disable (boolean disable)
          :on-action {:event/type :spring-lobby/random-map
                      :maps maps
                      :on-value-changed on-value-changed}
          :graphic
          {:fx/type font-icon/lifecycle
           :icon-literal (str "mdi-dice-" (inc (rand-nth (take 6 (iterate inc 0)))) ":16:white")}}}])
     [{:fx/type fx.ext.node/with-tooltip-props
       :props
       {:tooltip
        {:fx/type :tooltip
         :show-delay [10 :ms]
         :text "Reload maps"}}
       :desc
       {:fx/type :button
        :on-action {:event/type :spring-lobby/reload-maps}
        :graphic
        {:fx/type font-icon/lifecycle
         :icon-literal "mdi-refresh:16:white"}}}])})
