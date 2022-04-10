(ns skylobby.fx.download
  (:require
    [cljfx.api :as fx]
    [cljfx.ext.node :as fx.ext.node]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [skylobby.fs :as fs]
    skylobby.fx
    [skylobby.fx.ext :refer [ext-table-column-auto-size]]
    [skylobby.fx.font-icon :as font-icon]
    [skylobby.fx.tooltip-nofocus :as tooltip-nofocus]
    [skylobby.http :as http]
    [skylobby.resource :as resource]
    [skylobby.util :as u]
    [taoensso.tufte :as tufte])
  (:import
    (javafx.scene.input Clipboard ClipboardContent)))


(set! *warn-on-reflection* true)


(def download-window-width 1600)
(def download-window-height 800)


(defn- download-type-cell
  [download-type]
  {:text (if download-type
           (name download-type)
           " < nil > ")})

(defn- download-source-cell
  [{:keys [url download-source-name]}]
  {:text (str download-source-name " ( at " url " )")})

(defn downloader-root
  [{:fx/keys [context]}]
  (let [download-filter (fx/sub-val context :download-filter)
        download-type (fx/sub-val context :download-type)
        download-source-name (fx/sub-val context :download-source-name)
        downloadables-by-url (fx/sub-val context :downloadables-by-url)
        file-cache (fx/sub-val context :file-cache)
        http-download (fx/sub-val context :http-download)
        spring-isolation-dir (fx/sub-val context :spring-isolation-dir)
        download-source (->> http/download-sources
                         (filter (comp #{download-source-name} :download-source-name))
                         first)
        downloadables (->> (or (vals downloadables-by-url) [])
                           (filter :resource-type)
                           (filter (fn [downloadable]
                                     (if download-source-name
                                       (= download-source-name (:download-source-name downloadable))
                                       true)))
                           (filter (fn [{:keys [resource-filename resource-name]}]
                                     (if download-filter
                                       (or (and resource-filename
                                                (string/includes?
                                                  (string/lower-case resource-filename)
                                                  (string/lower-case download-filter)))
                                           (and resource-name
                                                (string/includes?
                                                  (string/lower-case resource-name)
                                                  (string/lower-case download-filter))))
                                       true)))
                           (filter (fn [{:keys [resource-type]}]
                                     (if download-type
                                       (= download-type resource-type)
                                       true)))
                           (sort-by (comp #(or % "") :resource-filename) String/CASE_INSENSITIVE_ORDER))]
    {:fx/type :v-box
     :style {:-fx-font-size 16}
     :children
     [{:fx/type :h-box
       :alignment :center-left
       :children
       (concat
         [{:fx/type :label
           :text " Filter source: "}
          {:fx/type :combo-box
           :value download-source
           :items (sort-by :download-source-name http/download-sources)
           :button-cell download-source-cell
           :prompt-text " < pick a source > "
           :cell-factory
           {:fx/cell-type :list-cell
            :describe download-source-cell}
           :on-value-changed {:event/type :spring-lobby/download-source-change}
           :tooltip {:fx/type tooltip-nofocus/lifecycle
                     :show-delay skylobby.fx/tooltip-show-delay
                     :text "Choose download source"}}]
         (when download-source
           [{:fx/type fx.ext.node/with-tooltip-props
             :props
             {:tooltip
              {:fx/type tooltip-nofocus/lifecycle
               :show-delay skylobby.fx/tooltip-show-delay
               :text "Clear source filter"}}
             :desc
             {:fx/type :button
              :on-action {:event/type :spring-lobby/dissoc
                          :key :download-source-name}
              :graphic
              {:fx/type font-icon/lifecycle
               :icon-literal "mdi-close:16:white"}}}
            {:fx/type fx.ext.node/with-tooltip-props
             :props
             {:tooltip
              {:fx/type tooltip-nofocus/lifecycle
               :show-delay skylobby.fx/tooltip-show-delay
               :text "Open download source url"}}
             :desc
             {:fx/type :button
              :on-action {:event/type :spring-lobby/desktop-browse-url
                          :url (or (:browse-url download-source)
                                   (:url download-source))}
              :graphic
              {:fx/type font-icon/lifecycle
               :icon-literal "mdi-web:16:white"}}}])
         [{:fx/type :button
           :text " Refresh "
           :on-action
           (if download-source
             {:event/type :spring-lobby/add-task
              :task
              (merge
                {:spring-lobby/task-type :spring-lobby/update-downloadables
                 :force true}
                download-source)}
             {:event/type :spring-lobby/add-task
              :task
              {:spring-lobby/task-type :spring-lobby/update-all-downloadables
               :force true}})
           :graphic
           {:fx/type font-icon/lifecycle
            :icon-literal "mdi-refresh:16:white"}}])}
      {:fx/type :h-box
       :alignment :center-left
       :style {:-fx-font-size 16}
       :children
       (concat
         [{:fx/type :label
           :text " Filter: "}
          {:fx/type :text-field
           :text download-filter
           :prompt-text "Filter by name or path"
           :on-text-changed {:event/type :spring-lobby/assoc
                             :key :download-filter}}]
         (when-not (string/blank? download-filter)
           [{:fx/type fx.ext.node/with-tooltip-props
             :props
             {:tooltip
              {:fx/type tooltip-nofocus/lifecycle
               :show-delay skylobby.fx/tooltip-show-delay
               :text "Clear filter"}}
             :desc
             {:fx/type :button
              :on-action {:event/type :spring-lobby/dissoc
                          :key :download-filter}
              :graphic
              {:fx/type font-icon/lifecycle
               :icon-literal "mdi-close:16:white"}}}])
         [{:fx/type :label
           :text " Filter type: "}
          {:fx/type :combo-box
           :value download-type
           :items resource/resource-types
           :button-cell download-type-cell
           :prompt-text " < pick a type > "
           :cell-factory
           {:fx/cell-type :list-cell
            :describe download-type-cell}
           :on-value-changed {:event/type :spring-lobby/assoc
                              :key :download-type}
           :tooltip {:fx/type tooltip-nofocus/lifecycle
                     :show-delay skylobby.fx/tooltip-show-delay
                     :text "Choose download type"}}]
         (when download-type
           [{:fx/type fx.ext.node/with-tooltip-props
             :props
             {:tooltip
              {:fx/type tooltip-nofocus/lifecycle
               :show-delay skylobby.fx/tooltip-show-delay
               :text "Clear type filter"}}
             :desc
             {:fx/type :button
              :on-action {:event/type :spring-lobby/dissoc
                          :key :download-type}
              :graphic
              {:fx/type font-icon/lifecycle
               :icon-literal "mdi-close:16:white"}}}]))}
      {:fx/type :label
       :text (str (count downloadables) " artifacts")
       :style {:-fx-font-size 14}}
      {:fx/type ext-table-column-auto-size
       :v-box/vgrow :always
       :items downloadables
       :desc
       {:fx/type :table-view
        :column-resize-policy :constrained
        :items downloadables
        :style {:-fx-font-size 16}
        :row-factory
        {:fx/cell-type :table-row
         :describe
         (fn [{:keys [download-url]}]
           {:context-menu
            {:fx/type :context-menu
             :items
             (concat []
               (when-not (string/blank? download-url)
                 [{:fx/type :menu-item
                   :text "Copy URL"
                   :on-action (fn [_event]
                                (let [clipboard (Clipboard/getSystemClipboard)
                                      content (ClipboardContent.)]
                                  (.putString content (str download-url))
                                  (.setContent clipboard content)))}]))}})}
        :columns
        [{:fx/type :table-column
          :text "Source"
          :pref-width 50
          :cell-value-factory :download-source-name
          :cell-factory
          {:fx/cell-type :table-cell
           :describe (fn [source] {:text (str source)})}}
         {:fx/type :table-column
          :text "Type"
          :pref-width 20
          :cell-value-factory :resource-type
          :cell-factory
          {:fx/cell-type :table-cell
           :describe download-type-cell}}
         {:fx/type :table-column
          :text "File"
          :pref-width 100
          :cell-value-factory :resource-filename
          :cell-factory
          {:fx/cell-type :table-cell
           :describe (fn [resource-filename] {:text (str resource-filename)})}}
         {:fx/type :table-column
          :pref-width 100
          :text "URL"
          :cell-value-factory :download-url
          :cell-factory
          {:fx/cell-type :table-cell
           :describe
           (fn [download-url]
             {:text (str download-url)
              :tooltip
              {:fx/type tooltip-nofocus/lifecycle
               :show-delay skylobby.fx/tooltip-show-delay
               :style {:-fx-font-size 18}
               :text (str download-url)}})}}
         {:fx/type :table-column
          :text "Download"
          :pref-width 50
          :sortable false
          :cell-value-factory identity
          :cell-factory
          {:fx/cell-type :table-cell
           :describe
           (fn [{:keys [download-url resource-filename] :as downloadable}]
             (let [dest-file (resource/resource-dest spring-isolation-dir downloadable)
                   dest-path (fs/canonical-path dest-file)
                   download (get http-download download-url)
                   in-progress (:running download)
                   extract-file (when dest-file
                                  (io/file spring-isolation-dir "engine" (fs/filename dest-file)))]
               {:text ""
                :graphic
                (cond
                  in-progress
                  {:fx/type :label
                   :text (str (u/download-progress download))}
                  (and (not in-progress)
                       (not (fs/file-exists? file-cache dest-path)))
                  {:fx/type :button
                   :tooltip
                   {:fx/type tooltip-nofocus/lifecycle
                    :show-delay skylobby.fx/tooltip-show-delay
                    :text (str "Download to " dest-path)}
                   :on-action {:event/type :spring-lobby/add-task
                               :task {:spring-lobby/task-type :spring-lobby/http-downloadable
                                      :downloadable downloadable
                                      :spring-isolation-dir spring-isolation-dir}}
                   :graphic
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-download:16:white"}}
                  (and
                       (fs/file-exists? file-cache dest-path)
                       dest-file
                       (or
                         (http/engine-archive? resource-filename)
                         (http/bar-engine-filename? resource-filename))
                       extract-file
                       (not (fs/file-exists? file-cache (fs/canonical-path extract-file))))
                  {:fx/type :button
                   :tooltip
                   {:fx/type tooltip-nofocus/lifecycle
                    :show-delay skylobby.fx/tooltip-show-delay
                    :text (str "Extract to " extract-file)}
                   :on-action
                   {:event/type :spring-lobby/extract-7z
                    :file dest-file
                    :dest extract-file}
                   :graphic
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-archive:16:white"}}
                  :else
                  {:fx/type font-icon/lifecycle
                   :icon-literal "mdi-check:16:white"})}))}}]}}]}))

(defn download-window-impl
  [{:fx/keys [context]
    :keys [screen-bounds]}]
  (let [
        show (boolean
               (and
                 (fx/sub-val context :show-downloader)
                 (not (fx/sub-val context :windows-as-tabs))))]
    {:fx/type :stage
     :showing show
     :title (str u/app-name " Downloader")
     :icons skylobby.fx/icons
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :show-downloader}
     :x (skylobby.fx/fitx screen-bounds)
     :y (skylobby.fx/fity screen-bounds)
     :width (skylobby.fx/fitwidth screen-bounds download-window-width)
     :height (skylobby.fx/fitheight screen-bounds download-window-height)
     :scene
     {:fx/type :scene
      :stylesheets (fx/sub-ctx context skylobby.fx/stylesheet-urls-sub)
      :root
      (if show
        {:fx/type downloader-root}
        {:fx/type :pane
         :pref-width download-window-width
         :pref-height download-window-height})}}))


(defn download-window [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :download-window
      (download-window-impl state))))
