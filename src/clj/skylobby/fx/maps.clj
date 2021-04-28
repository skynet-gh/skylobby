(ns skylobby.fx.maps
  (:require
    [cljfx.ext.node :as fx.ext.node]
    [clojure.java.io :as io]
    [clojure.string :as string]
    skylobby.fx
    [skylobby.resource :as resource]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.fs :as fs]
    [spring-lobby.util :as u]
    [taoensso.tufte :as tufte]))


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


(defn maps-view
  [{:keys [disable downloadables-by-url http-download map-name maps on-value-changed
           map-input-prefix spring-isolation-dir suggest tasks-by-type]}]
  (tufte/profile {:id :skylobby/ui}
    (tufte/p :maps-window
      {:fx/type :h-box
       :alignment :center-left
       :children
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
                           task (->> (get tasks-by-type :spring-lobby/http-downloadable)
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
            :on-action {:event/type :spring-lobby/add-task
                        :task {:spring-lobby/task-type :spring-lobby/reconcile-maps}}
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal "mdi-refresh:16:white"}}}])})))


(def maps-window-keys
  [:filter-maps-name :maps :on-change-map :show-maps])

(defn maps-window
  [{:keys [filter-maps-name maps on-change-map screen-bounds show-maps]}]
  (let [{:keys [width height]} screen-bounds]
    {:fx/type :stage
     :showing (boolean show-maps)
     :title (str u/app-name " Maps")
     :icons skylobby.fx/icons
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :show-maps}
     :width (min maps-window-width width)
     :height (min maps-window-height height)
     :scene
     {:fx/type :scene
      :stylesheets skylobby.fx/stylesheets
      :root
      (if show-maps
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
                  {:fx/type :tooltip
                   :show-delay [10 :ms]
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
                 {:fx/type :tooltip
                  :text (str map-name)
                  :show-delay [10 :ms]
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
                     (sort-by :map-name))))}}]}
        {:fx/type :pane})}}))
