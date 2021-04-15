(ns skylobby.fx.mods
  (:require
    [cljfx.ext.node :as fx.ext.node]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [spring-lobby.fx.font-icon :as font-icon]
    [version-clj.core :as version]))


(defn mods-view [{:keys [mod-filter mod-name mods on-value-changed spring-isolation-dir]}]
  (let [games (filter :is-game mods)]
    {:fx/type :h-box
     :alignment :center-left
     :children
     (concat
       [{:fx/type :label
         :alignment :center-left
         :text " Game: "}]
       (if (empty? games)
         [{:fx/type :label
           :text "No games "}]
         (let [filter-lc (if mod-filter (string/lower-case mod-filter) "")
               filtered-games (->> games
                                   (map :mod-name)
                                   (filter string?)
                                   (filter #(string/includes? (string/lower-case %) filter-lc))
                                   (sort version/version-compare))]
           [{:fx/type :combo-box
             :prompt-text " < pick a game > "
             :value mod-name
             :items filtered-games
             :on-value-changed (or on-value-changed
                                   {:event/type :spring-lobby/assoc
                                    :key :mod-name})
             :cell-factory
             {:fx/cell-type :list-cell
              :describe
              (fn [mod-name]
                {:text (if (string/blank? mod-name)
                         "< choose a game >"
                         mod-name)})}
             :on-key-pressed {:event/type :spring-lobby/mods-key-pressed}
             :on-hidden {:event/type :spring-lobby/mods-hidden}}]))
       [{:fx/type fx.ext.node/with-tooltip-props
         :props
         {:tooltip
          {:fx/type :tooltip
           :show-delay [10 :ms]
           :text "Open games directory"}}
         :desc
         {:fx/type :button
          :on-action {:event/type :spring-lobby/desktop-browse-dir
                      :file (io/file spring-isolation-dir "games")}
          :graphic
          {:fx/type font-icon/lifecycle
           :icon-literal "mdi-folder:16:white"}}}
        {:fx/type fx.ext.node/with-tooltip-props
         :props
         {:tooltip
          {:fx/type :tooltip
           :show-delay [10 :ms]
           :text "Reload games"}}
         :desc
         {:fx/type :button
          :on-action {:event/type :spring-lobby/reload-mods}
          :graphic
          {:fx/type font-icon/lifecycle
           :icon-literal "mdi-refresh:16:white"}}}])}))
