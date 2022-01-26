(ns skylobby.fx.engines
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


(def known-engine-versions
  ["104.0.1-1828-g1f481b7 BAR"])


(defn engines-view-impl
  [{:fx/keys [context]
    :keys [engine-version flow on-value-changed spring-isolation-dir suggest]
    :or {flow true}}]
  (let [downloadables-by-url (fx/sub-val context :downloadables-by-url)
        engine-filter (fx/sub-val context :engine-filter)
        {:keys [engines engines-by-version]} (fx/sub-ctx context sub/spring-resources spring-isolation-dir)
        spring-root-path (fs/canonical-path spring-isolation-dir)
        on-value-changed (or on-value-changed
                             {:event/type :spring-lobby/assoc-in
                              :path [:by-spring-root spring-root-path :engine-version]})
        engine-override (fs/canonical-path (fx/sub-val context get-in [:engine-overrides spring-root-path engine-version]))
        indexed-engines (fx/sub-ctx context sub/indexed-engines spring-isolation-dir engine-version)
        engine-details (or (first (filter (comp #{engine-override} fs/canonical-path :file) indexed-engines))
                           (fx/sub-ctx context sub/indexed-engine spring-isolation-dir engine-version))
        http-download (fx/sub-val context :http-download)
        http-download-tasks (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/download-and-extract)
        selected-engine-file (:file engine-details)]
    {:fx/type :v-box
     :children
     (concat
       [
        (merge
          {:fx/type (if flow :flow-pane :h-box)}
          (when-not flow {:alignment :center-left})
          {:children
           (concat
             [{:fx/type :label
               :text " Engine: "}]
             (if (empty? engines)
               (if suggest
                 (let [downloadables (vals downloadables-by-url)]
                   (mapv
                     (fn [engine-version]
                       (let [downloadable (->> downloadables
                                               (filter (partial resource/could-be-this-engine? engine-version))
                                               first)
                             download (get http-download (:download-url downloadable))
                             running (:running download)
                             task (->> http-download-tasks
                                       (filter (comp (partial resource/same-resource-filename? downloadable) :downloadable))
                                       seq)]
                         {:fx/type :button
                          :text (cond
                                  running (u/download-progress download)
                                  task "Queued..."
                                  :else
                                  (str "Get " engine-version))
                          :disable (boolean (or (not downloadable) running task))
                          :on-action {:event/type :spring-lobby/add-task
                                      :task {:spring-lobby/task-type :spring-lobby/download-and-extract
                                             :downloadable downloadable
                                             :spring-isolation-dir spring-isolation-dir}}}))
                     known-engine-versions))
                [{:fx/type :label
                  :text " No engines "}])
              (let [filter-lc (if engine-filter (string/lower-case engine-filter) "")
                    filtered-engines (->> engines-by-version
                                          keys
                                          (filter some?)
                                          (filter #(string/includes? (string/lower-case %) filter-lc))
                                          sort)]
                [{:fx/type ext-recreate-on-key-changed
                  :key (str engine-version)
                  :desc
                  {:fx/type :combo-box
                   :prompt-text " < pick an engine > "
                   :value engine-version
                   :items filtered-engines
                   :on-value-changed on-value-changed
                   :cell-factory
                   {:fx/cell-type :list-cell
                    :describe
                    (fn [engine]
                      {:text (if (string/blank? engine)
                               "< choose an engine >"
                               engine)})}
                   :on-key-pressed {:event/type :spring-lobby/engines-key-pressed}
                   :on-hidden {:event/type :spring-lobby/dissoc
                               :key :engine-filter}}}]))
             [{:fx/type fx.ext.node/with-tooltip-props
               :props
               {:tooltip
                {:fx/type tooltip-nofocus/lifecycle
                 :show-delay skylobby.fx/tooltip-show-delay
                 :text "View engine directory"}}
               :desc
               {:fx/type :button
                :on-action {:event/type :spring-lobby/desktop-browse-dir
                            :file (or selected-engine-file
                                      (fs/file spring-isolation-dir "engine"))}
                :graphic
                {:fx/type font-icon/lifecycle
                 :icon-literal "mdi-folder:16:white"}}}
              {:fx/type fx.ext.node/with-tooltip-props
               :props
               {:tooltip
                {:fx/type tooltip-nofocus/lifecycle
                 :show-delay skylobby.fx/tooltip-show-delay
                 :text "Reload engines"}}
               :desc
               {:fx/type :button
                :disable (boolean
                           (seq
                             (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/refresh-engines)))
                :on-action {:event/type :spring-lobby/add-task
                            :task {:spring-lobby/task-type :spring-lobby/refresh-engines
                                   :force true
                                   :spring-root spring-isolation-dir}}
                :graphic
                {:fx/type font-icon/lifecycle
                 :icon-literal "mdi-refresh:16:white"}}}])})]
       (when (< 1 (count indexed-engines))
         [{:fx/type :h-box
           :alignment :center-left
           :style {:-fx-font-size 18}
           :children
           [
            {:fx/type :label
             :text " Path: "}
            {:fx/type :combo-box
             :value selected-engine-file
             :items (sort (map :file indexed-engines))
             :on-value-changed {:event/type :spring-lobby/assoc-in
                                :path [:engine-overrides spring-root-path engine-version]}
             :button-cell
             (fn [file]
               {:text (str (fs/filename file))})}]}]))}))

(defn engines-view [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :engines-view
      (engines-view-impl state))))
