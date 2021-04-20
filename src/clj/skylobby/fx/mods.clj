(ns skylobby.fx.mods
  (:require
    [cljfx.ext.node :as fx.ext.node]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [skylobby.resource :as resource]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.util :as u]
    [version-clj.core :as version]))


(def known-games
  [
   {:mod-name "Beyond All Reason"
    :version "byar:test"
    :source :rapid}
   {:mod-name "Metal Factions"
    :version "metalfactions:test"
    :source :rapid}
   {:mod-name "Balanced Annihilation"
    :version "Balanced Annihilation V12.1.0"
    :source :http}])


(defn mods-view
  [{:keys [downloadables-by-url engine-file http-download mod-filter mod-name mods on-value-changed
           rapid-data-by-id rapid-download spring-isolation-dir suggest tasks-by-type]}]
  (let [games (filter :is-game mods)]
    {:fx/type :h-box
     :alignment :center-left
     :children
     (concat
       [{:fx/type :label
         :alignment :center-left
         :text " Game: "}]
       (if (empty? games)
         (if suggest
           [{:fx/type :flow-pane
             :children
             (let [downloadables (vals downloadables-by-url)]
               (map
                 (fn [{:keys [mod-name source version]}]
                   (let [downloadable (case source
                                        :rapid (:id (get rapid-data-by-id version))
                                        :http (->> downloadables
                                                   (filter (partial resource/could-be-this-mod? version))
                                                   first)
                                        nil)
                         on-action (case source
                                     :rapid
                                     {:event/type :spring-lobby/add-task
                                      :task
                                      (if downloadable
                                        {:spring-lobby/task-type :spring-lobby/rapid-download
                                         :rapid-id version
                                         :engine-file engine-file
                                         :spring-isolation-dir spring-isolation-dir}
                                        {:spring-lobby/task-type :spring-lobby/update-rapid
                                         :force true})}
                                     :http
                                     {:event/type :spring-lobby/add-task
                                      :task {:spring-lobby/task-type :spring-lobby/http-downloadable
                                             :downloadable downloadable
                                             :spring-isolation-dir spring-isolation-dir}}
                                     nil)
                         download (or (get http-download (:download-url downloadable))
                                      (get rapid-download downloadable))
                         running (:running download)
                         task (seq
                                (concat
                                  (filter
                                    (comp #{downloadable} :id)
                                    (get tasks-by-type :spring-lobby/rapid-download))
                                  (filter
                                    (comp (partial resource/same-resource-filename? downloadable) :downloadable)
                                    (get tasks-by-type :spring-lobby/http-downloadable))))]
                     {:fx/type :button
                      :text (if (and (not downloadable) (= :rapid source))
                              "Update rapid"
                              (cond
                                running (u/download-progress download)
                                task "Queued..."
                                :else
                                (str "Get " mod-name)))
                      :disable (boolean
                                 (or
                                   (and (not downloadable)
                                        (not= :rapid source))
                                   running
                                   task))
                      :on-action on-action}))
                 known-games))}]
           [{:fx/type :label
             :text " No games"}])
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
          :on-action {:event/type :spring-lobby/add-task
                      :task {:spring-lobby/task-type :spring-lobby/reconcile-mods}}
          :graphic
          {:fx/type font-icon/lifecycle
           :icon-literal "mdi-refresh:16:white"}}}])}))
