(ns skylobby.fx.engines
  (:require
    [cljfx.ext.node :as fx.ext.node]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [skylobby.resource :as resource]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.util :as u]))


(def known-engine-versions
  ["104.0.1-1828-g1f481b7 BAR"])


(defn engines-view
  [{:keys [downloadables-by-url engine-filter engine-version engines flow http-download on-value-changed
           spring-isolation-dir suggest tasks-by-type]
    :or {flow true}}]
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
                       task (->> (get tasks-by-type :spring-lobby/download-and-extract)
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
              filtered-engines (->> engines
                                    (map :engine-version)
                                    (filter some?)
                                    (filter #(string/includes? (string/lower-case %) filter-lc))
                                    sort)]
          [{:fx/type :combo-box
            :prompt-text " < pick an engine > "
            :value engine-version
            :items filtered-engines
            :on-value-changed (or on-value-changed
                                  {:event/type :spring-lobby/assoc
                                   :key :engine-version})
            :cell-factory
            {:fx/cell-type :list-cell
             :describe
             (fn [engine]
               {:text (if (string/blank? engine)
                        "< choose an engine >"
                        engine)})}
            :on-key-pressed {:event/type :spring-lobby/engines-key-pressed}
            :on-hidden {:event/type :spring-lobby/dissoc
                        :key :engine-filter}}]))
       [{:fx/type fx.ext.node/with-tooltip-props
         :props
         {:tooltip
          {:fx/type :tooltip
           :show-delay [10 :ms]
           :text "Open engine directory"}}
         :desc
         {:fx/type :button
          :on-action {:event/type :spring-lobby/desktop-browse-dir
                      :file (io/file spring-isolation-dir "engine")}
          :graphic
          {:fx/type font-icon/lifecycle
           :icon-literal "mdi-folder:16:white"}}}
        {:fx/type fx.ext.node/with-tooltip-props
         :props
         {:tooltip
          {:fx/type :tooltip
           :show-delay [10 :ms]
           :text "Reload engines"}}
         :desc
         {:fx/type :button
          :on-action {:event/type :spring-lobby/add-task
                      :task {:spring-lobby/task-type :spring-lobby/refresh-engines}}
          :graphic
          {:fx/type font-icon/lifecycle
           :icon-literal "mdi-refresh:16:white"}}}])}))
