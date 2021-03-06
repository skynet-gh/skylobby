(ns skylobby.fx.spring-info
  (:require
    [cljfx.api :as fx]
    [clojure.string :as string]
    skylobby.fx
    [skylobby.fx.font-icon :as font-icon]
    [skylobby.fx.rich-text :as fx.rich-text]
    [skylobby.fx.sub :as sub]
    [skylobby.fx.sync :refer [error-severity]]
    [skylobby.fx.virtualized-scroll-pane :as fx.virtualized-scroll-pane]
    [skylobby.util :as u])
  (:import
    (org.fxmisc.richtext.model ReadOnlyStyledDocumentBuilder SegmentOps StyledSegment)))


(set! *warn-on-reflection* true)


(def spring-info-window-width 1200)
(def spring-info-window-height 1200)

(def window-key :spring-info)


(defn segment
  [text style]
  (StyledSegment. text style))

(defn spring-info-root
  [{:fx/keys [context]}]
  (let [spring-log (fx/sub-val context :spring-log)]
    {:fx/type :v-box
     :style {:-fx-font-size 16}
     :children
     (concat
       [
        {:fx/type :label
         :style {:-fx-font-size 24}
         :text "Spring crashed"}
        {:fx/type :label
         :style {:-fx-font-size 18}
         :text "infolog:"}
        {:fx/type fx.virtualized-scroll-pane/lifecycle
         :v-box/vgrow :always
         :event-filter {:event/type :spring-lobby/filter-channel-scroll}
         :content
         {:fx/type fx.rich-text/lifecycle-fast
          :editable false
          :style
          {:-fx-font-family skylobby.fx/monospace-font-family}
          :wrap-text true
          :document
          [spring-log
           (fn [logs]
             (let [builder (ReadOnlyStyledDocumentBuilder. (SegmentOps/styledTextOps) "")]
               (doseq [log logs]
                 (let [{:keys [line stream]} log]
                   (.addParagraph builder
                     ^java.util.List
                     (vec
                       (concat
                         [(segment
                            (str line)
                            ["text" (str "skylobby-spring-log-" (name stream))])]))
                     ^java.util.List
                     [])))
               (when (seq logs)
                 (.build builder))))]}}
        {:fx/type :button
         :text "View Log File"
         :on-action {:event/type :spring-lobby/desktop-browse-dir
                     :file (fx/sub-val context :spring-crash-infolog-file)}
         :graphic
         {:fx/type font-icon/lifecycle
          :icon-literal "mdi-folder:16:white"}}]
       (when-let [{:keys [archive-name resolved-archive-name]} (fx/sub-val context :spring-crash-archive-not-found)]
         (let [spring-root (fx/sub-val context :spring-isolation-dir)
               {:keys [maps maps-by-name mods mods-by-name]} (fx/sub-ctx context sub/spring-resources spring-root)
               matching-map (or (get maps-by-name resolved-archive-name)
                                (->> maps (filter :map-name) (filter (comp #{archive-name} string/lower-case :map-name)) first))
               matching-mod (or (get mods-by-name resolved-archive-name)
                                (->> mods (filter :mod-name) (filter (comp #{archive-name} string/lower-case :mod-name)) first))
               map-tasks (seq
                           (concat
                             (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/delete-corrupt-map-file)
                             (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/refresh-maps)))
               mod-tasks (seq
                           (concat
                             (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/delete-corrupt-mod-file)
                             (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/refresh-mods)))
               deleting (cond
                          matching-map (boolean map-tasks)
                          matching-mod (boolean mod-tasks)
                          :else false)]
           (when (or matching-map matching-mod)
             [{:fx/type :h-box
               :alignment :center-left
               :children
               [{:fx/type :label
                 :text " Suggested action: "}
                {:fx/type :button
                 :style (dissoc error-severity :-fx-background-color)
                 :text (str (if deleting "Deleting" "Delete")
                            " corrupt "
                            (if matching-map "map " "game ")
                            (or resolved-archive-name archive-name)
                            (when deleting "..."))
                 :disable deleting
                 :on-action
                 {
                  :event/type :spring-lobby/add-task
                  :task
                  (if matching-map
                    {:spring-lobby/task-type :spring-lobby/delete-corrupt-map-file
                     :indexed-map matching-map
                     :spring-root spring-root}
                    {:spring-lobby/task-type :spring-lobby/delete-corrupt-mod-file
                     :indexed-mod matching-mod
                     :spring-root spring-root})}}]}]))))}))


(defn spring-info-window
  [{:fx/keys [context]
    :keys [screen-bounds]}]
  (let [
        show (boolean (fx/sub-val context :show-spring-info-window))
        window-states (fx/sub-val context :window-states)]
    {:fx/type :stage
     :showing (boolean show)
     :title (str u/app-name " Spring Info")
     :icons skylobby.fx/icons
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :show-spring-info-window}
     :x (skylobby.fx/fitx screen-bounds (get-in window-states [window-key :x]))
     :y (skylobby.fx/fity screen-bounds (get-in window-states [window-key :y]))
     :width (skylobby.fx/fitwidth screen-bounds (get-in window-states [window-key :width]) spring-info-window-width)
     :height (skylobby.fx/fitheight screen-bounds (get-in window-states [window-key :height]) spring-info-window-height)
     :on-width-changed (partial skylobby.fx/window-changed window-key :width)
     :on-height-changed (partial skylobby.fx/window-changed window-key :height)
     :on-x-changed (partial skylobby.fx/window-changed window-key :x)
     :on-y-changed (partial skylobby.fx/window-changed window-key :y)
     :scene
     {:fx/type :scene
      :stylesheets (fx/sub-ctx context skylobby.fx/stylesheet-urls-sub)
      :root
      (if show
        {:fx/type spring-info-root}
        {:fx/type :pane
         :pref-width spring-info-window-width
         :pref-height spring-info-window-height})}}))
