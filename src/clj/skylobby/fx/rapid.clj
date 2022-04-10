(ns skylobby.fx.rapid
  (:require
    [cljfx.api :as fx]
    [cljfx.ext.node :as fx.ext.node]
    [clojure.java.io :as io]
    [clojure.string :as string]
    skylobby.fx
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed ext-table-column-auto-size]]
    [skylobby.fx.font-icon :as font-icon]
    [skylobby.fx.tooltip-nofocus :as tooltip-nofocus]
    [skylobby.fs :as fs]
    [skylobby.rapid :as rapid]
    [skylobby.util :as u]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(def rapid-download-window-width 1600)
(def rapid-download-window-height 800)


(defn rapid-download-root
  [{:fx/keys [context]}]
  (let [
        rapid-download (fx/sub-val context :rapid-download)
        rapid-filter (fx/sub-val context :rapid-filter)
        rapid-repo (fx/sub-val context :rapid-repo)
        spring-isolation-dir (fx/sub-val context :spring-isolation-dir)
        spring-root (or (fx/sub-val context :rapid-spring-root)
                        spring-isolation-dir)
        spring-root-path (fs/canonical-path spring-root)
        rapid-repos (fx/sub-val context get-in [:rapid-by-spring-root spring-root-path :rapid-repos])
        rapid-versions (fx/sub-val context get-in [:rapid-by-spring-root spring-root-path :rapid-versions])
        rapid-packages (fx/sub-val context get-in [:rapid-by-spring-root spring-root-path :rapid-packages])
        sdp-files (fx/sub-val context get-in [:rapid-by-spring-root spring-root-path :sdp-files])
        engine-version (fx/sub-val context get-in [:by-spring-root spring-root-path :engine-version])
        engines (fx/sub-val context get-in [:by-spring-root spring-root-path :engines])
        servers (fx/sub-val context :servers)
        spring-roots (fs/spring-roots {:servers servers :spring-isolation-dir spring-isolation-dir})
        spring-roots-paths (mapv fs/canonical-path spring-roots)
        tasks-by-type (fx/sub-ctx context skylobby.fx/tasks-by-type-sub)
        sdp-files (or sdp-files [])
        sdp-hashes (set (map rapid/sdp-hash sdp-files))
        sorted-engine-versions (->> engines
                                    (map :engine-version)
                                    (sort skylobby.fx/case-insensitive-natural-comparator))
        filtered-rapid-versions (->> rapid-versions
                                     (filter
                                       (fn [{:keys [id]}]
                                         (if rapid-repo
                                           (string/starts-with? id (str rapid-repo ":"))
                                           true)))
                                     (filter
                                       (fn [{:keys [version] :as i}]
                                         (if-not (string/blank? rapid-filter)
                                           (or
                                             (string/includes?
                                               (string/lower-case version)
                                               (string/lower-case rapid-filter))
                                             (string/includes?
                                               (:hash i)
                                               (string/lower-case rapid-filter)))
                                           true))))
        engines-by-version (into {} (map (juxt :engine-version identity) engines))
        engine-file (:file (get engines-by-version engine-version))
        rapid-updating (seq (get tasks-by-type :spring-lobby/update-rapid))
        rapid-tasks-by-id (->> (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/rapid-download)
                               (map (juxt :rapid-id identity))
                               (into {}))
        available-packages (or (->> filtered-rapid-versions
                                    seq
                                    (sort-by :version skylobby.fx/case-insensitive-natural-comparator)
                                    reverse)
                               [])
        local-packages (or (->> rapid-packages
                                seq
                                (sort-by :version skylobby.fx/case-insensitive-natural-comparator))
                           [])]
    {:fx/type :v-box
     :children
     [{:fx/type :h-box
       :style {:-fx-font-size 16}
       :alignment :center-left
       :children
       [{:fx/type :label
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
                             :key :rapid-spring-root}}}]}
      {:fx/type :h-box
       :style {:-fx-font-size 16}
       :alignment :center-left
       :children
       [{:fx/type :label
         :text " Engine for pr-downloader: "}
        {:fx/type :combo-box
         :value (str engine-version)
         :items (or (seq sorted-engine-versions)
                    [])
         :on-value-changed {:event/type :spring-lobby/assoc
                            :key :engine-version}}
        {:fx/type :button
         :text (if rapid-updating " Refreshing..." " Refresh ")
         :disable (boolean rapid-updating)
         :on-action {:event/type :spring-lobby/add-task
                     :task {:spring-lobby/task-type :spring-lobby/update-rapid
                            :force true
                            :rapid-repo rapid-repo
                            :spring-isolation-dir spring-root}}
         :graphic
         {:fx/type font-icon/lifecycle
          :icon-literal "mdi-refresh:16:white"}}]}
      {:fx/type :h-box
       :style {:-fx-font-size 16}
       :alignment :center-left
       :children
       (concat
         [{:fx/type :label
           :text " Filter Repo: "}
          {:fx/type :combo-box
           :value (str rapid-repo)
           :items (or (seq rapid-repos)
                      [])
           :on-value-changed {:event/type :spring-lobby/rapid-repo-change}}]
         (when rapid-repo
           [{:fx/type fx.ext.node/with-tooltip-props
             :props
             {:tooltip
              {:fx/type tooltip-nofocus/lifecycle
               :show-delay skylobby.fx/tooltip-show-delay
               :text "Clear rapid repo filter"}}
             :desc
             {:fx/type :button
              :on-action {:event/type :spring-lobby/dissoc
                          :key :rapid-repo}
              :graphic
              {:fx/type font-icon/lifecycle
               :icon-literal "mdi-close:16:white"}}}])
         [{:fx/type :label
           :text " Rapid Filter: "}
          {:fx/type :text-field
           :text rapid-filter
           :prompt-text "Filter by name or path"
           :on-text-changed {:event/type :spring-lobby/assoc
                             :key :rapid-filter}}]
         (when-not (string/blank? rapid-filter)
           [{:fx/type fx.ext.node/with-tooltip-props
             :props
             {:tooltip
              {:fx/type tooltip-nofocus/lifecycle
               :show-delay skylobby.fx/tooltip-show-delay
               :text "Clear filter"}}
             :desc
             {:fx/type :button
              :on-action {:event/type :spring-lobby/dissoc
                          :key :rapid-filter}
              :graphic
              {:fx/type font-icon/lifecycle
               :icon-literal "mdi-close:16:white"}}}]))}
      {:fx/type :label
       :text " Available Packages"
       :style {:-fx-font-size 16}}
      {:fx/type ext-table-column-auto-size
       :v-box/vgrow :always
       :items available-packages
       :desc
       {:fx/type :table-view
        :items available-packages
        :column-resize-policy :constrained
        :style {:-fx-font-size 16}
        :columns
        [{:fx/type :table-column
          :sortable false
          :text "ID"
          :pref-width 480
          :resizable false
          :cell-value-factory identity
          :cell-factory
          {:fx/cell-type :table-cell
           :describe
           (fn [i]
             {:text (str (:id i))})}}
         {:fx/type :table-column
          :sortable false
          :text "Hash"
          :pref-width 320
          :resizable false
          :cell-value-factory identity
          :cell-factory
          {:fx/cell-type :table-cell
           :describe
           (fn [i]
             {:text (str (:hash i))})}}
         {:fx/type :table-column
          :text "Version"
          :comparator skylobby.fx/case-insensitive-natural-comparator
          :pref-width 100
          :min-width 320
          :cell-value-factory :version
          :cell-factory
          {:fx/cell-type :table-cell
           :describe
           (fn [version]
             {:text (str version)})}}
         {:fx/type :table-column
          :text "Download"
          :sortable false
          :pref-width 800
          :cell-value-factory identity
          :cell-factory
          {:fx/cell-type :table-cell
           :describe
           (fn [i]
             (let [download (get rapid-download (:id i))]
               (merge
                 {:text (str (:message download))
                  :style {:-fx-font-family skylobby.fx/monospace-font-family}}
                 (cond
                   (sdp-hashes (:hash i))
                   {:graphic
                    {:fx/type font-icon/lifecycle
                     :icon-literal "mdi-check:16:white"}}
                   (:running download)
                   nil
                   (not engine-file)
                   {:text "Needs an engine"}
                   :else
                   {:graphic
                    {:fx/type :button
                     :disable (boolean (contains? rapid-tasks-by-id (:id i)))
                     :on-action {:event/type :spring-lobby/add-task
                                 :task
                                 {:spring-lobby/task-type :spring-lobby/rapid-download
                                  :engine-file engine-file
                                  :rapid-id (:id i)
                                  :spring-isolation-dir spring-root}}
                     :graphic
                     {:fx/type font-icon/lifecycle
                      :icon-literal "mdi-download:16:white"}}}))))}}]}}
      {:fx/type :h-box
       :alignment :center-left
       :style {:-fx-font-size 16}
       :children
       [{:fx/type :label
         :text " Packages"}
        {:fx/type fx.ext.node/with-tooltip-props
         :props
         {:tooltip
          {:fx/type tooltip-nofocus/lifecycle
           :show-delay skylobby.fx/tooltip-show-delay
           :text "Open rapid packages directory"}}
         :desc
         {:fx/type :button
          :on-action {:event/type :spring-lobby/desktop-browse-dir
                      :file (io/file spring-root "packages")}
          :graphic
          {:fx/type font-icon/lifecycle
           :icon-literal "mdi-folder:16:white"}}}]}
      {:fx/type ext-table-column-auto-size
       :v-box/vgrow :always
       :items local-packages
       :desc
       {:fx/type :table-view
        :column-resize-policy :constrained
        :items local-packages
        :style {:-fx-font-size 16}
        :columns
        [{:fx/type :table-column
          :text "Filename"
          :pref-width 100
          :sortable false
          :cell-value-factory identity
          :cell-factory
          {:fx/cell-type :table-cell
           :describe
           (fn [i] {:text (:filename i)})}}
         {:fx/type :table-column
          :sortable false
          :text "ID"
          :pref-width 100
          :cell-value-factory identity
          :cell-factory
          {:fx/cell-type :table-cell
           :describe
           (fn [i] {:text (:id i)})}}
         {:fx/type :table-column
          :text "Version"
          :comparator skylobby.fx/case-insensitive-natural-comparator
          :pref-width 100
          :cell-value-factory :version
          :cell-factory
          {:fx/cell-type :table-cell
           :describe
           (fn [version] {:text (str version)})}}]}}]}))


(defn rapid-download-window-impl
  [{:fx/keys [context]
    :keys [screen-bounds]}]
  (let [
        show (boolean
               (and
                 (fx/sub-val context :show-rapid-downloader)
                 (not (fx/sub-val context :show-rapid-downloader))))]
    {:fx/type :stage
     :showing show
     :title (str u/app-name " Rapid Downloader")
     :icons skylobby.fx/icons
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :show-rapid-downloader}
     :x (skylobby.fx/fitx screen-bounds)
     :y (skylobby.fx/fity screen-bounds)
     :width (skylobby.fx/fitwidth screen-bounds rapid-download-window-width)
     :height (skylobby.fx/fitheight screen-bounds rapid-download-window-height)
     :scene
     {:fx/type :scene
      :stylesheets (fx/sub-ctx context skylobby.fx/stylesheet-urls-sub)
      :root
      (if show
        {:fx/type rapid-download-root}
        {:fx/type :pane
         :pref-width rapid-download-window-width
         :pref-height rapid-download-window-height})}}))

(defn rapid-download-window [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :rapid-download-window
      (rapid-download-window-impl state))))
