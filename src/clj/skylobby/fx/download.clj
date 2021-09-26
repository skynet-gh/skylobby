(ns skylobby.fx.download
  (:require
    [cljfx.ext.node :as fx.ext.node]
    [clojure.java.io :as io]
    [clojure.string :as string]
    skylobby.fx
    [skylobby.resource :as resource]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.fs :as fs]
    [spring-lobby.http :as http]
    [spring-lobby.util :as u]
    [taoensso.tufte :as tufte]))


(def download-window-width 1600)
(def download-window-height 800)


(def springfiles-maps-download-source
  {:download-source-name "SpringFiles Maps"
   :url http/springfiles-maps-url
   :resources-fn http/html-downloadables})

(def hakora-maps-download-source
  {:download-source-name "Hakora Maps"
   :url "http://www.hakora.xyz/files/springrts/maps"
   :resources-fn http/html-downloadables})

(def download-sources
  [;springfiles-maps-download-source gone now
   hakora-maps-download-source
   {:download-source-name "Balanced Annihilation GitHub releases"
    :url http/tap-github-releases-url
    :browse-url "https://github.com/Balanced-Annihilation/Balanced-Annihilation/releases"
    :resources-fn http/get-github-release-downloadables
    :resource-type-fn #(when (string/ends-with? % ".sdz") :spring-lobby/mod)}
   {:download-source-name "BAR GitHub spring"
    :url http/bar-spring-releases-url
    :browse-url "https://github.com/beyond-all-reason/spring/releases"
    :resources-fn http/get-github-release-engine-downloadables}
   {:download-source-name "BAR GitHub maps"
    :url http/bar-maps-github-releases-url
    :browse-url "https://github.com/beyond-all-reason/Maps/releases"
    :resources-fn http/get-bar-maps-github-release-downloadables}
   {:download-source-name "Evolution-RTS GitHub releases"
    :url http/evo-rts-github-releases-url
    :browse-url "https://github.com/EvolutionRTS/Evolution-RTS/releases"
    :resources-fn http/get-evo-rts-github-release-downloadables
    :resource-type-fn #(when (http/evo-rts-filename? %) :spring-lobby/mod)}
   {:download-source-name "SpringFightClub Maps"
    :url (str http/springfightclub-root "/maps")
    :resources-fn http/html-downloadables}
   {:download-source-name "SpringFightClub Games"
    :url http/springfightclub-root
    :resources-fn (partial http/html-downloadables
                           (fn [url]
                             (when (and url (string/ends-with? url ".sdz"))
                               :spring-lobby/mod)))}
   {:download-source-name "SpringLauncher"
    :url http/springlauncher-root
    :resources-fn http/get-springlauncher-downloadables}
   {:download-source-name "SpringRTS buildbot"
    :url http/springrts-buildbot-root
    :resources-fn http/crawl-springrts-engine-downloadables}
   {:download-source-name "TAP GitHub releases"
    :url http/tap-github-releases-url
    :browse-url "https://github.com/FluidPlay/TAPrime_v2/releases"
    :resources-fn http/get-github-release-downloadables
    :resource-type-fn #(when (string/ends-with? % ".sdz") :spring-lobby/mod)}
   {:download-source-name "TAP GitHub maps"
    :url http/tap-maps-github-releases-url
    :browse-url "https://github.com/FluidPlay/TAPrime-maps/releases"
    :resources-fn http/get-bar-maps-github-release-downloadables}])


(defn- download-type-cell
  [download-type]
  {:text (if download-type
           (name download-type)
           " < nil > ")})

(defn- download-source-cell
  [{:keys [url download-source-name]}]
  {:text (str download-source-name " ( at " url " )")})


(def download-window-keys
  [:css :download-filter :download-source-name :download-type :downloadables-by-url :file-cache
   :http-download :show-downloader :show-stale :spring-isolation-dir])

(defn download-window-impl
  [{:keys [css download-filter download-type download-source-name downloadables-by-url file-cache
           http-download screen-bounds show-downloader spring-isolation-dir]}]
  (let [download-source (->> download-sources
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
                                       true))))
        {:keys [width height]} screen-bounds]
    {:fx/type :stage
     :showing (boolean show-downloader)
     :title (str u/app-name " Downloader")
     :icons skylobby.fx/icons
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :show-downloader}
     :width ((fnil min download-window-width) width download-window-width)
     :height ((fnil min download-window-height) height download-window-height)
     :scene
     {:fx/type :scene
      :stylesheets (skylobby.fx/stylesheet-urls css)
      :root
      (if show-downloader
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
               :items (sort-by :download-source-name download-sources)
               :button-cell download-source-cell
               :prompt-text " < pick a source > "
               :cell-factory
               {:fx/cell-type :list-cell
                :describe download-source-cell}
               :on-value-changed {:event/type :spring-lobby/download-source-change}
               :tooltip {:fx/type :tooltip
                         :show-delay [10 :ms]
                         :text "Choose download source"}}]
             (when download-source
               [{:fx/type fx.ext.node/with-tooltip-props
                 :props
                 {:tooltip
                  {:fx/type :tooltip
                   :show-delay [10 :ms]
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
                  {:fx/type :tooltip
                   :show-delay [10 :ms]
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
                 {:event/type :spring-lobby/update-all-downloadables
                  :force true})
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
                  {:fx/type :tooltip
                   :show-delay [10 :ms]
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
               :tooltip {:fx/type :tooltip
                         :show-delay [10 :ms]
                         :text "Choose download type"}}]
             (when download-type
               [{:fx/type fx.ext.node/with-tooltip-props
                 :props
                 {:tooltip
                  {:fx/type :tooltip
                   :show-delay [10 :ms]
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
          {:fx/type :table-view
           :column-resize-policy :constrained ; TODO auto resize
           :v-box/vgrow :always
           :items downloadables
           :style {:-fx-font-size 16}
           :columns
           [{:fx/type :table-column
             :text "Source"
             :cell-value-factory :download-source-name
             :cell-factory
             {:fx/cell-type :table-cell
              :describe (fn [source] {:text (str source)})}}
            {:fx/type :table-column
             :text "Type"
             :cell-value-factory :resource-type
             :cell-factory
             {:fx/cell-type :table-cell
              :describe download-type-cell}}
            {:fx/type :table-column
             :text "File"
             :cell-value-factory :resource-filename
             :cell-factory
             {:fx/cell-type :table-cell
              :describe (fn [resource-filename] {:text (str resource-filename)})}}
            {:fx/type :table-column
             :text "URL"
             :cell-value-factory :download-url
             :cell-factory
             {:fx/cell-type :table-cell
              :describe (fn [download-url] {:text (str download-url)})}}
            {:fx/type :table-column
             :text "Download"
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
                      {:fx/type :tooltip
                       :show-delay [10 :ms]
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
                      {:fx/type :tooltip
                       :show-delay [10 :ms]
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
                      :icon-literal "mdi-check:16:white"})}))}}]}]}
       {:fx/type :pane})}}))


(defn download-window [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :download-window
      (download-window-impl state))))
