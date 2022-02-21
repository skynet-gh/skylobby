(ns skylobby.fx.mods
  (:require
    [cljfx.api :as fx]
    [cljfx.ext.node :as fx.ext.node]
    [clojure.string :as string]
    [skylobby.fs :as fs]
    skylobby.fx
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed]]
    [skylobby.fx.font-icon :as font-icon]
    [skylobby.fx.sub :as sub]
    [skylobby.fx.tooltip-nofocus :as tooltip-nofocus]
    [skylobby.resource :as resource]
    [skylobby.util :as u]
    [taoensso.tufte :as tufte]))


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


(defn mods-view-impl
  [{:fx/keys [context]
    :keys [engine-file flow mod-name on-value-changed spring-isolation-dir suggest]
    :or {flow true}}]
  (let [downloadables-by-url (fx/sub-val context :downloadables-by-url)
        http-download (fx/sub-val context :http-download)
        rapid-download (fx/sub-val context :rapid-download)
        {:keys [mods-by-name]} (fx/sub-ctx context sub/spring-resources spring-isolation-dir)
        http-download-tasks (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/http-downloadable)
        rapid-download-tasks (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/rapid-download)
        games (fx/sub-ctx context sub/games spring-isolation-dir)
        spring-root-path (fs/canonical-path spring-isolation-dir)
        on-value-changed (or on-value-changed
                             {:event/type :spring-lobby/assoc-in
                              :path [:by-spring-root spring-root-path :mod-name]})
        selected-mod-file (:file (get mods-by-name mod-name))]
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
           (let [
                 filtered-games (fx/sub-ctx context sub/filtered-games spring-isolation-dir)]
             [{:fx/type ext-recreate-on-key-changed
               :key (str mod-name)
               :desc
               {:fx/type :combo-box
                :prompt-text " < pick a game > "
                :value mod-name
                :items filtered-games
                :on-value-changed on-value-changed
                :cell-factory
                {:fx/cell-type :list-cell
                 :describe
                 (fn [mod-name]
                   {:text (if (string/blank? mod-name)
                            "< choose a game >"
                            mod-name)})}
                :on-key-pressed {:event/type :spring-lobby/mods-key-pressed}
                :on-hidden {:event/type :spring-lobby/dissoc
                            :key :mod-filter}}}]))
         [{:fx/type fx.ext.node/with-tooltip-props
           :props
           {:tooltip
            {:fx/type tooltip-nofocus/lifecycle
             :show-delay skylobby.fx/tooltip-show-delay
             :text "View game file"}}
           :desc
           {:fx/type :button
            :on-action {:event/type :spring-lobby/desktop-browse-dir
                        :file (or selected-mod-file
                                  (fs/file spring-isolation-dir "games"))}
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
            :disable (boolean
                       (seq
                         (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/refresh-mods)))
            :on-action {:event/type :spring-lobby/add-task
                        :task {:spring-lobby/task-type :spring-lobby/refresh-mods
                               :spring-root spring-isolation-dir
                               :priorities [selected-mod-file]}}
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal "mdi-refresh:16:white"}}}])})))


(defn mods-view [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :mods-view
      (mods-view-impl state))))
