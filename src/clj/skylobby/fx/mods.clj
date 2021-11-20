(ns skylobby.fx.mods
  (:require
    [cljfx.api :as fx]
    [cljfx.ext.node :as fx.ext.node]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [skylobby.fs :as fs]
    skylobby.fx
    [skylobby.fx.font-icon :as font-icon]
    [skylobby.fx.tooltip-nofocus :as tooltip-nofocus]
    [skylobby.resource :as resource]
    [skylobby.util :as u]
    [taoensso.tufte :as tufte]
    [version-clj.core :as version]))


(set! *warn-on-reflection* true)


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


(defn- mods-view-impl
  [{:fx/keys [context]
    :keys [engine-file flow mod-name on-value-changed spring-isolation-dir suggest]
    :or {flow true}}]
  (let [downloadables-by-url (fx/sub-val context :downloadables-by-url)
        http-download (fx/sub-val context :http-download)
        rapid-download (fx/sub-val context :rapid-download)
        mod-filter (fx/sub-val context :mod-filter)
        mods (fx/sub-val context get-in [:by-spring-root (fs/canonical-path spring-isolation-dir) :mods])
        http-download-tasks (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/http-downloadable)
        rapid-download-tasks (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/rapid-download)
        games (filter :is-game mods)
        spring-root-path (fs/canonical-path spring-isolation-dir)]
    (merge
      {:fx/type (if flow :flow-pane :h-box)}
      (when-not flow {:alignment :center-left})
      {:children
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
                                          :rapid (:id (fx/sub-val context get-in [:rapid-by-spring-root spring-root-path :rapid-data-by-id version]))
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
                                      rapid-download-tasks)
                                    (filter
                                      (comp (partial resource/same-resource-filename? downloadable) :downloadable)
                                      http-download-tasks)))]
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
               :on-hidden {:event/type :spring-lobby/dissoc
                           :key :mod-filter}}]))
         [{:fx/type fx.ext.node/with-tooltip-props
           :props
           {:tooltip
            {:fx/type tooltip-nofocus/lifecycle
             :show-delay skylobby.fx/tooltip-show-delay
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
            {:fx/type tooltip-nofocus/lifecycle
             :show-delay skylobby.fx/tooltip-show-delay
             :text "Reload games"}}
           :desc
           {:fx/type :button
            :on-action {:event/type :spring-lobby/add-task
                        :task {:spring-lobby/task-type :spring-lobby/refresh-mods}}
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal "mdi-refresh:16:white"}}}])})))


(defn mods-view [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :mods-view
      (mods-view-impl state))))
