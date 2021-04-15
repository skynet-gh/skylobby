(ns skylobby.fx.engines
  (:require
    [cljfx.ext.node :as fx.ext.node]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [spring-lobby.fx.font-icon :as font-icon]))


(defn engines-view
  [{:keys [engine-filter engine-version engines on-value-changed spring-isolation-dir]}]
  {:fx/type :h-box
   :alignment :center-left
   :children
   (concat
     [{:fx/type :label
       :text " Engine: "}]
     (if (empty? engines)
       [{:fx/type :label
         :text "No engines "}]
       (let [filter-lc (if engine-filter (string/lower-case engine-filter) "")
             filtered-engines (->> engines
                                   (map :engine-version)
                                   (filter some?)
                                   (filter #(string/includes? (string/lower-case %) filter-lc))
                                   sort)]
         [{:fx/type :combo-box
           :prompt-text " < pick an engine > "
           :value engine-version
           :items filtered-engines
           :on-value-changed (or on-value-changed
                                 {:event/type :spring-lobby/assoc
                                  :key :engine-version})
           :cell-factory
           {:fx/cell-type :list-cell
            :describe
            (fn [engine]
              {:text (if (string/blank? engine)
                       "< choose an engine >"
                       engine)})}
           :on-key-pressed {:event/type :spring-lobby/engines-key-pressed}
           :on-hidden {:event/type :spring-lobby/engines-hidden}}]))
     [{:fx/type fx.ext.node/with-tooltip-props
       :props
       {:tooltip
        {:fx/type :tooltip
         :show-delay [10 :ms]
         :text "Open engine directory"}}
       :desc
       {:fx/type :button
        :on-action {:event/type :spring-lobby/desktop-browse-dir
                    :file (io/file spring-isolation-dir "engine")}
        :graphic
        {:fx/type font-icon/lifecycle
         :icon-literal "mdi-folder:16:white"}}}
      {:fx/type fx.ext.node/with-tooltip-props
       :props
       {:tooltip
        {:fx/type :tooltip
         :show-delay [10 :ms]
         :text "Reload engines"}}
       :desc
       {:fx/type :button
        :on-action {:event/type :spring-lobby/reconcile-engines}
        :graphic
        {:fx/type font-icon/lifecycle
         :icon-literal "mdi-refresh:16:white"}}}])})
