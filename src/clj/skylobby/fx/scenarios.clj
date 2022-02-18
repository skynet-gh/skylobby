(ns skylobby.fx.scenarios
  (:require
    [cljfx.api :as fx]
    [cljfx.ext.table-view :as fx.ext.table-view]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [skylobby.datalevin :as db]
    [skylobby.fs :as fs]
    skylobby.fx
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed ext-table-column-auto-size]]
    [skylobby.fx.map-sync :refer [map-sync-pane]]
    [skylobby.fx.mod-sync :refer [mod-sync-pane]]
    [skylobby.fx.sub :as sub]
    [skylobby.resource :as resource]
    [skylobby.util :as u]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(def scenarios-window-width 1600)
(def scenarios-window-height 1200)


(def table-height 300)

(def mod-name-prefix "Beyond All Reason test-")


(defn scenarios-root
  [{:fx/keys [context]}]
  (let [
        spring-isolation-dir (fx/sub-val context :spring-isolation-dir)
        spring-root (or (fx/sub-val context :scenarios-spring-root)
                        spring-isolation-dir)
        spring-root-path (fs/canonical-path spring-root)
        {:keys [rapid-data-by-hash rapid-data-by-id]} (fx/sub-val context get-in [:rapid-by-spring-root spring-root-path])
        latest-rapid-id "byar-chobby:test"
        datalevin (fx/sub-val context :datalevin)
        use-db-for-rapid (fx/sub-val context :use-db-for-rapid)
        rapid-data (if use-db-for-rapid
                     (db/rapid-data-by-id datalevin spring-root-path latest-rapid-id)
                     (get rapid-data-by-id latest-rapid-id))
        rapid-hash (:hash rapid-data)
        rapid-id (if use-db-for-rapid
                   (db/rapid-id-by-hash datalevin spring-root-path rapid-hash)
                   (:id (get rapid-data-by-hash rapid-hash)))
        rapid-version (:version rapid-data)
        {:keys [engines engines-by-version mods-by-name]} (fx/sub-ctx context sub/spring-resources spring-root)
        latest-local-bar (->> mods-by-name
                              keys
                              (filter #(string/starts-with? % mod-name-prefix))
                              sort
                              last)
        indexed-mod (get mods-by-name rapid-version)
        sorted-engine-versions (->> engines
                                    (map :engine-version)
                                    (sort skylobby.fx/case-insensitive-natural-comparator))
        engine-version (or (fx/sub-val context :scenarios-engine-version)
                           (->> engines
                                (map :engine-version)
                                (filter some?)
                                (filter #(string/includes? % "BAR"))
                                sort
                                last))
        engine-file (:file (get engines-by-version engine-version))
        servers (fx/sub-val context :servers)
        spring-roots (fs/spring-roots {:servers servers :spring-isolation-dir spring-isolation-dir})
        spring-roots-paths (mapv fs/canonical-path spring-roots)
        mod-details (fx/sub-ctx context skylobby.fx/mod-details-sub indexed-mod)
        selected-scenario-name (fx/sub-val context :selected-scenario)
        scenario-difficulty (fx/sub-val context :scenario-difficulty)
        scenario-side (fx/sub-val context :scenario-side)
        mod-details-chobby-tasks (->> (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/mod-details)
                                      (filter :mod-name)
                                      (filter (comp #(string/includes? % "chobby") string/lower-case :mod-name))
                                      seq)
        loading-scenarios (boolean mod-details-chobby-tasks)
        rapid-packages-tasks (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/update-rapid-packages)
        rapid-tasks-by-id (->> (concat
                                 (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/rapid-download)
                                 (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/update-rapid))
                               (map (juxt :rapid-id identity))
                               (into {}))
        some-task (or (contains? rapid-tasks-by-id rapid-id)
                      (seq rapid-packages-tasks))]
    {:fx/type :v-box
     :alignment :top-center
     :style {:-fx-font-size 16}
     :children
     (concat
       [{:fx/type :h-box
         :alignment :center
         :children
         [
          {:fx/type :label
           :text " Spring root: "}
          {:fx/type ext-recreate-on-key-changed
           :key (str spring-root-path spring-roots-paths)
           :desc
           {:fx/type :combo-box
            :value spring-root
            :items (or spring-roots [])
            :cell-factory
            {:fx/cell-type :list-cell
             :describe (fn [f] {:text (str f)})}
            :on-value-changed {:event/type :spring-lobby/assoc
                               :key :scenarios-spring-root}}}]}
        {:fx/type :h-box
         :alignment :center
         :children
         [
          {:fx/type :label
           :text " Spring engine version: "}
          {:fx/type :combo-box
           :value (str engine-version)
           :items (or (seq sorted-engine-versions)
                      [])
           :on-value-changed {:event/type :spring-lobby/assoc-in
                              :key :scenarios-engine-version}}]}
        {:fx/type :label
         :text (str " Latest version is " rapid-version)}]
       (if indexed-mod
         [
          {:fx/type :button
           :disable (boolean loading-scenarios)
           :text (if loading-scenarios
                   " Loading scenarios... "
                   (if (resource/details? mod-details)
                     " Reload scenarios "
                     " Load scenarios "))
           :on-action
           {:event/type :spring-lobby/add-task
            :task
            {:spring-lobby/task-type :spring-lobby/mod-details
             :mod-name rapid-version
             :mod-file (:file indexed-mod)}}}]
         [
          {:fx/type :button
           :text (cond
                   (not engine-version) "Pick an engine"
                   some-task
                   (str "Getting latest scenarios: " rapid-id "...")
                   :else
                   "Get latest scenarios")
           :disable (boolean (or some-task
                                 (not engine-version)))
           :on-action
           {:event/type :spring-lobby/add-task
            :task
            {:spring-lobby/task-type :spring-lobby/rapid-download
             :engine-file engine-file
             :rapid-id (or rapid-id latest-rapid-id)
             :spring-isolation-dir spring-root}}}])
       (when (resource/details? mod-details)
         (let [scenarios (->> mod-details
                              :scenarios
                              (filter :lua)
                              (sort-by (comp u/to-number :difficulty :lua)))
               selected-scenario (->> scenarios
                                      (filter (comp #{selected-scenario-name} :scenario))
                                      first)]
           [{:fx/type :v-box
             :v-box/vgrow :always
             :alignment :top-center
             :children
             (concat
               [
                {:fx/type fx.ext.table-view/with-selection-props
                 :props {:selection-mode :single
                         :on-selected-item-changed {:event/type :spring-lobby/select-scenario}
                         :selected-item selected-scenario}
                 :desc
                 {:fx/type ext-table-column-auto-size
                  :items scenarios
                  :desc
                  {:fx/type :table-view
                   :style {:-fx-min-height 200
                           :-fx-pref-height table-height
                           :-fx-max-height table-height}
                   :items scenarios
                   :columns
                   [
                    #_
                    {:fx/type :table-column
                     :text "Scenario"
                     :sortable false
                     :cell-value-factory :scenario
                     :cell-factory
                     {:fx/cell-type :table-cell
                      :describe
                      (fn [scenario]
                        {:text (str scenario)})}}
                    {:fx/type :table-column
                     :text "Title"
                     :sortable false
                     :pref-width 200
                     :cell-value-factory (comp :title :lua)
                     :cell-factory
                     {:fx/cell-type :table-cell
                      :describe
                      (fn [title]
                        {:text (str title)})}}
                    {:fx/type :table-column
                     :text "Difficulty"
                     :resizable false
                     :pref-width 80
                     :cell-value-factory (comp u/to-number :difficulty :lua)
                     :cell-factory
                     {:fx/cell-type :table-cell
                      :describe
                      (fn [difficulty]
                        {:text (str difficulty)})}}
                    {:fx/type :table-column
                     :text "Summary"
                     :sortable false
                     :cell-value-factory (comp :summary :lua)
                     :cell-factory
                     {:fx/cell-type :table-cell
                      :describe
                      (fn [summary]
                        {:text (str summary)})}}]}}}]
               (when selected-scenario
                 (let [{:keys [image-file lua]} selected-scenario
                       difficulty (or scenario-difficulty (:defaultdifficulty lua))
                       map-name (:mapfilename lua)
                       sides (or (vals (:allowedsides lua)) [])
                       side (or scenario-side (last sides))]
                   [{:fx/type :scroll-pane
                     :fit-to-width true
                     :content
                     {:fx/type :h-box
                      :children
                      (concat
                        [{:fx/type :v-box
                          :children
                          [
                           {:fx/type :label
                            :style {:-fx-font-size 20}
                            :text (str (:title lua))}
                           {:fx/type :label
                            :text (str " Author: " (:author lua))}
                           {:fx/type :label
                            :text " Summary: "
                            :style {:-fx-font-size 18}}
                           {:fx/type :label
                            :text (str (:summary lua))
                            :style {:-fx-font-size 18}
                            :wrap-text true}
                           {:fx/type :label
                            :text " Briefing: "}
                           {:fx/type :label
                            :text (str (:briefing lua))
                            :wrap-text true}
                           {:fx/type :pane
                            :style {:-fx-pref-height 16}}
                           {:fx/type :label
                            :style {:-fx-font-size 18}
                            :text (str " Map: " (:mapfilename lua))}
                           {:fx/type :label
                            :style {:-fx-font-size 18}
                            :text (str " Victory: " (:victorycondition lua))}
                           {:fx/type :label
                            :style {:-fx-font-size 18}
                            :text (str " Loss: " (:losscondition lua))}
                           #_
                           {:fx/type :label
                            :text " Start script: "}
                           #_
                           {:fx/type :text-area
                            :text (str (:startscript lua))}]}]
                        (when image-file
                          [{:fx/type :v-box
                            :children
                            [{:fx/type :image-view
                              :image {:url (-> image-file io/as-url str)
                                      :background-loading true}
                              :fit-width 800
                              :preserve-ratio true}
                             {:fx/type :h-box
                              :children
                              [{:fx/type :v-box
                                :h-box/margin 8
                                :children
                                [
                                 {:fx/type :label
                                  :text (str " Par time: " (:partime lua))}
                                 {:fx/type :label
                                  :text (str " Par resources: " (:parresources lua))}]}
                               {:fx/type :v-box
                                :h-box/margin 8
                                :children
                                [
                                 {:fx/type :label
                                  :text " Difficulty: "}
                                 {:fx/type ext-recreate-on-key-changed
                                  :key (str difficulty)
                                  :desc
                                  {:fx/type :combo-box
                                   :value difficulty
                                   :items (or (vals (:difficulties lua)) [])
                                   :cell-factory
                                   {:fx/cell-type :list-cell
                                    :describe
                                    (fn [difficulty]
                                      {:text (str (:name difficulty))})}
                                   :button-cell
                                   (fn [difficulty]
                                     {:text (str (:name difficulty))})
                                   :on-value-changed {:event/type :spring-lobby/assoc
                                                      :key :scenario-difficulty}}}]}
                               {:fx/type :v-box
                                :h-box/margin 8
                                :children
                                [
                                 {:fx/type :label
                                  :text " Side: "}
                                 {:fx/type ext-recreate-on-key-changed
                                  :key (str side)
                                  :desc
                                  {:fx/type :combo-box
                                   :value side
                                   :items sides
                                   :cell-factory
                                   {:fx/cell-type :list-cell
                                    :describe
                                    (fn [side]
                                      {:text (str side)})}
                                   :on-value-changed {:event/type :spring-lobby/assoc
                                                      :key :scenario-side}}}]}]}
                             {:fx/type :pane
                              :style {:-fx-pref-height 8}}
                             {:fx/type :h-box
                              :children
                              [
                               {:fx/type map-sync-pane
                                :h-box/margin 8
                                :index-only true
                                :map-name map-name
                                :spring-isolation-dir spring-root}
                               {:fx/type mod-sync-pane
                                :h-box/margin 8
                                :engine-version engine-version
                                :index-only true
                                :mod-name (or latest-local-bar
                                              mod-name-prefix) ; to show rapid update button
                                :spring-isolation-dir spring-root}]}]}]))}}
                    {:fx/type :button
                     :style {:-fx-font-size 24
                             :-fx-margin-bottom 8}
                     :v-box/vgrow :always
                     :text "Play Scenario"
                     :on-action
                     {:event/type :spring-lobby/play-scenario
                      :difficulties (or (vals (:difficulties lua)) [])
                      :engine-version engine-version
                      :engines-by-version engines-by-version
                      :mod-name latest-local-bar
                      :scenario-options (:scenariooptions lua)
                      :script-template (:startscript lua)
                      :script-params
                      {:difficulty difficulty
                       :side side
                       :player-name "Me"
                       :map-name map-name
                       :restricted-units (:unitlimits lua)}
                      :spring-isolation-dir spring-root}}
                    {:fx/type :pane
                     :style {:-fx-pref-height 8}}])))}])))}))


(defn scenarios-window-impl
  [{:fx/keys [context]
    :keys [screen-bounds]}]
  (let [show (boolean
               (and
                 (fx/sub-val context :show-scenarios-window)
                 (not (fx/sub-val context :windows-as-tabs))))]
    {:fx/type :stage
     :showing show
     :title (str u/app-name " Scenarios")
     :icons skylobby.fx/icons
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :show-scenarios-window}
     :x (skylobby.fx/fitx screen-bounds)
     :y (skylobby.fx/fity screen-bounds)
     :width (skylobby.fx/fitwidth screen-bounds scenarios-window-width)
     :height (skylobby.fx/fitheight screen-bounds scenarios-window-height)
     :scene
     {:fx/type :scene
      :stylesheets (fx/sub-ctx context skylobby.fx/stylesheet-urls-sub)
      :root
      (if show
        {:fx/type scenarios-root}
        {:fx/type :pane
         :pref-width scenarios-window-width
         :pref-height scenarios-window-height})}}))

(defn scenarios-window [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :scenarios-window
      (scenarios-window-impl state))))
