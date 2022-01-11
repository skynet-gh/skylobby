(ns skylobby.fx.console
  (:require
    [cljfx.api :as fx]
    skylobby.fx
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed ext-scroll-on-create]]
    [skylobby.fx.rich-text :as fx.rich-text]
    [skylobby.fx.virtualized-scroll-pane :as fx.virtualized-scroll-pane]
    [skylobby.util :as u]
    [taoensso.tufte :as tufte])
  (:import
    (org.fxmisc.richtext.model ReadOnlyStyledDocumentBuilder SegmentOps StyledSegment)))


(set! *warn-on-reflection* true)


(def default-font-size 18)


(defn segment
  [text style]
  (StyledSegment. text style))

(defn console-document [console-log]
  (let [
        builder (ReadOnlyStyledDocumentBuilder. (SegmentOps/styledTextOps) "")]
    (doseq [log console-log]
      (let [{:keys [message source timestamp]} log]
        (.addParagraph builder
          ^java.util.List
          (vec
            (concat
              [
               (segment
                 (str "[" (u/format-hours timestamp) "]")
                 ["text" "skylobby-console-time"])
               (segment
                 (str
                   (case source
                     :server " < "
                     :client " > "
                     " "))
                 ["text" (str "skylobby-console-source-" (name source))])
               (segment
                 (str message)
                 ["text" "skylobby-console-message"])]))
          ^java.util.List
          [])))
    (when (seq console-log)
      (.build builder))))

(defn console-view-impl
  [{:fx/keys [context]
    :keys [server-key]}]
  (let [
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        console-auto-scroll (fx/sub-val context :console-auto-scroll)
        console-log (fx/sub-val context get-in [:by-server server-key :console-log])
        console-message-draft (fx/sub-val context get-in [:by-server server-key :console-message-draft])
        selected-tab-main (fx/sub-val context get-in [:selected-tab-main server-key])
        console-ignore-message-types (or (fx/sub-val context :console-ignore-message-types)
                                         {})
        message-types (->> console-log
                           (map :message-type)
                           (filter some?)
                           set
                           sort)
        console-log (remove
                      (fn [{:keys [message-type]}]
                        (get console-ignore-message-types message-type))
                      console-log)]
    {:fx/type :h-box
     :children
     (if (= "console" selected-tab-main)
       [{:fx/type :v-box
         :h-box/hgrow :always
         :children
         [
          {:fx/type ext-recreate-on-key-changed
           :key {:server-key server-key
                 :ignore (set (map first (filter second console-ignore-message-types)))}
           :v-box/vgrow :always
           :desc
           {:fx/type ext-scroll-on-create
            :desc
            {:fx/type fx.virtualized-scroll-pane/lifecycle
             :event-filter {:event/type :spring-lobby/filter-console-scroll}
             :content
             {:fx/type fx.rich-text/lifecycle-fast
              :editable false
              :style {:-fx-font-family skylobby.fx/monospace-font-family
                      :-fx-font-size default-font-size}
              :wrap-text true
              :document [(reverse console-log) console-document console-auto-scroll]}}}}
          {:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :button
             :text "Send"
             :on-action {:event/type :spring-lobby/send-console
                         :client-data client-data
                         :message console-message-draft
                         :server-key server-key}}
            {:fx/type :text-field
             :h-box/hgrow :always
             :id "console-text-field"
             :text (str console-message-draft)
             :on-text-changed {:event/type :spring-lobby/assoc-in
                               :path [:by-server server-key :console-message-draft]}
             :on-action {:event/type :spring-lobby/send-console
                         :client-data client-data
                         :message console-message-draft
                         :server-key server-key}
             :on-key-pressed {:event/type :spring-lobby/on-console-key-pressed
                              :server-key server-key}}]}]}
        {:fx/type :v-box
         :children
         [{:fx/type :label
           :text "Ignore message types:"}
          {:fx/type :scroll-pane
           :v-box/vgrow :always
           :fit-to-width true
           :content
           {:fx/type :v-box
            :children
            (mapv
              (fn [t]
                {:fx/type :h-box
                 :children
                 [{:fx/type :check-box
                   :selected (boolean (get console-ignore-message-types t))
                   :on-selected-changed {:event/type :spring-lobby/assoc-in
                                         :path [:console-ignore-message-types t]}}
                  {:fx/type :label
                   :text (str t)}]})
              message-types)}}
          {:fx/type :h-box
           :children
           [{:fx/type :button
             :text "Select All"
             :on-action {:event/type :spring-lobby/assoc
                         :key :console-ignore-message-types
                         :value (into {}
                                  (map
                                    (fn [t] [t true])
                                    message-types))}}
            {:fx/type :button
             :text "Deselect All"
             :on-action {:event/type :spring-lobby/assoc
                         :key :console-ignore-message-types
                         :value {}}}]}]}]
       [])}))

(defn console-view
  [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :console-view
      (console-view-impl state))))
