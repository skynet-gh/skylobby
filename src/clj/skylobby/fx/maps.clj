(ns skylobby.fx.maps
  (:require
    [cljfx.api :as fx]
    [cljfx.ext.node :as fx.ext.node]
    [clojure.java.io :as io]
    [clojure.string :as string]
    skylobby.fx
    [skylobby.fx.sub :as sub]
    [skylobby.fx.tooltip-nofocus :as tooltip-nofocus]
    [skylobby.resource :as resource]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.fs :as fs]
    [spring-lobby.util :as u]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(def maps-window-width 1600)
(def maps-window-height 800)


(def minimap-size 512)


(def map-browse-image-size 162)
(def map-browse-box-height 224)


(def known-maps
  ["Altair_Crossing_V4"
   "Avalanche 3.4"
   "Nuclear Winter Bar 1.2"
   "Quicksilver Remake 1.24"
   "Red Comet Remake 1.8"])


(defn maps-view-impl
  [{:fx/keys [context]
    :keys [disable flow map-name on-value-changed spring-isolation-dir suggest]
    :or {flow true}}]
  (let [downloadables-by-url (fx/sub-val context :downloadables-by-url)
        http-download (fx/sub-val context :http-download)
        map-input-prefix (fx/sub-val context :map-input-prefix)
        http-download-tasks (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/http-downloadable)
        maps (fx/sub-val context get-in [:by-spring-root (fs/canonical-path spring-isolation-dir) :maps])]
    (merge
      {:fx/type (if flow :flow-pane :h-box)}
      (when-not flow {:alignment :center-left})
      {:children
       (concat
         [{:fx/type :label
           :alignment :center-left
           :text " Map: "}]
         (if (empty? maps)
           (if suggest
             (let [downloadables (vals downloadables-by-url)]
               [{:fx/type :flow-pane
                 :children
                 (map
                   (fn [map-name]
                     (let [downloadable (->> downloadables
                                             (filter (partial resource/could-be-this-map? map-name))
                                             first)
                           download (get http-download (:download-url downloadable))
                           running (:running download)
                           task (->> http-download-tasks
                                     (filter (comp (partial resource/same-resource-filename? downloadable) :downloadable))
                                     seq)]
                       {:fx/type :button
                        :text (cond
                                running (u/download-progress download)
                                task "Qeueued..."
                                :else
                                (str "Get " map-name))
                        :disable (boolean (or (not downloadable) running task))
                        :on-action {:event/type :spring-lobby/add-task
                                    :task {:spring-lobby/task-type :spring-lobby/http-downloadable
                                           :downloadable downloadable
                                           :spring-isolation-dir spring-isolation-dir}}}))
                   known-maps)}])
             [{:fx/type :label
               :text " No maps"}])
           (let [filter-lc (if map-input-prefix (string/lower-case map-input-prefix) "")
                 filtered-map-names
                 (->> maps
                      (map :map-name)
                      (filter string?)
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
               :on-hidden {:event/type :spring-lobby/dissoc
                           :key :map-input-prefix}}]))
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
            {:fx/type tooltip-nofocus/lifecycle
             :show-delay skylobby.fx/tooltip-show-delay
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
              {:fx/type tooltip-nofocus/lifecycle
               :show-delay skylobby.fx/tooltip-show-delay
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
            {:fx/type tooltip-nofocus/lifecycle
             :show-delay skylobby.fx/tooltip-show-delay
             :text "Reload maps"}}
           :desc
           {:fx/type :button
            :on-action {:event/type :spring-lobby/add-task
                        :task {:spring-lobby/task-type :spring-lobby/refresh-maps}}
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal "mdi-refresh:16:white"}}}])})))

(defn maps-view [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :maps-view
      (maps-view-impl state))))


(defn maps-window-impl
  [{:fx/keys [context]
    :keys [screen-bounds]}]
  (let [
        show-maps (fx/sub-val context :show-maps)]
    {:fx/type :stage
     :showing (boolean show-maps)
     :title (str u/app-name " Maps")
     :icons skylobby.fx/icons
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :show-maps}
     :x (skylobby.fx/fitx screen-bounds)
     :y (skylobby.fx/fity screen-bounds)
     :width (skylobby.fx/fitwidth screen-bounds maps-window-width)
     :height (skylobby.fx/fitheight screen-bounds maps-window-height)
     :scene
     {:fx/type :scene
      :stylesheets (fx/sub-ctx context skylobby.fx/stylesheet-urls-sub)
      :root
      (if show-maps
        (let [
              filter-maps-name (fx/sub-val context :filter-maps-name)
              on-change-map (fx/sub-val context :on-change-map)
              server-key (fx/sub-ctx context skylobby.fx/selected-tab-server-key-sub)
              spring-root (fx/sub-ctx context sub/spring-root server-key)
              maps (fx/sub-val context get-in [:by-spring-root (fs/canonical-path spring-root) :maps])]
          {:fx/type :v-box
           :children
           [{:fx/type :h-box
             :alignment :center-left
             :style {:-fx-font-size 16}
             :children
             (concat
               [{:fx/type :label
                 :text " Filter: "}
                {:fx/type :text-field
                 :text (str filter-maps-name)
                 :prompt-text "Filter by name or path"
                 :on-text-changed {:event/type :spring-lobby/assoc
                                   :key :filter-maps-name}}]
               (when-not (string/blank? filter-maps-name)
                 [{:fx/type fx.ext.node/with-tooltip-props
                   :props
                   {:tooltip
                    {:fx/type tooltip-nofocus/lifecycle
                     :show-delay skylobby.fx/tooltip-show-delay
                     :text "Clear filter"}}
                   :desc
                   {:fx/type :button
                    :on-action {:event/type :spring-lobby/dissoc
                                :key :filter-maps-name}
                    :graphic
                    {:fx/type font-icon/lifecycle
                     :icon-literal "mdi-close:16:white"}}}]))}
            {:fx/type :scroll-pane
             :fit-to-width true
             :content
             {:fx/type :flow-pane
              :vgap 5
              :hgap 5
              :padding 5
              :children
              (map
                (fn [{:keys [map-name]}]
                  {:fx/type :button
                   :style
                   {:-fx-min-width map-browse-image-size
                    :-fx-max-width map-browse-image-size
                    :-fx-min-height map-browse-box-height
                    :-fx-max-height map-browse-box-height}
                   :on-action {:event/type :spring-lobby/map-window-action
                               :on-change-map (assoc on-change-map :map-name map-name :value map-name)}
                   :tooltip
                   {:fx/type tooltip-nofocus/lifecycle
                    :text (str map-name)
                    :show-delay skylobby.fx/tooltip-show-delay
                    :style {:-fx-font-size 20}
                    :content-display :top
                    :graphic
                    {:fx/type :image-view
                     :image {:url (-> map-name fs/minimap-image-cache-file io/as-url str)
                             :background-loading true}
                     :preserve-ratio true
                     :style
                     {:-fx-min-width minimap-size
                      :-fx-max-width minimap-size
                      :-fx-min-height minimap-size
                      :-fx-max-height minimap-size}}}
                   :graphic
                   {:fx/type :v-box
                    :alignment :center
                    :children
                    [
                     {:fx/type :pane
                      :v-box/vgrow :always}
                     {:fx/type :image-view
                      :image {:url (-> map-name fs/minimap-image-cache-file io/as-url str)
                              :background-loading true}
                      :fit-width map-browse-image-size
                      :fit-height map-browse-image-size
                      :preserve-ratio true}
                     {:fx/type :pane
                      :v-box/vgrow :always}
                     {:fx/type :label
                      :style {:-fx-font-size 16}
                      :text (str " " map-name)
                      :wrap-text true}]}})
                (let [filter-lc ((fnil string/lower-case "") filter-maps-name)]
                  (->> maps
                       (filter (fn [{:keys [map-name]}]
                                 (and map-name
                                      (string/includes? (string/lower-case map-name) filter-lc))))
                       (sort-by :map-name))))}}]})
        {:fx/type :pane
         :pref-width maps-window-width
         :pref-height maps-window-height})}}))

(defn maps-window [state]
  (tufte/profile {:dyanmic? true
                  :id :skylobby/ui}
    (tufte/p :maps-view
      (maps-window-impl state))))
