(ns skylobby.fx.console
  (:require
    [cljfx.api :as fx]
    skylobby.fx
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed ext-scroll-on-create]]
    [skylobby.fx.rich-text :as fx.rich-text]
    [skylobby.fx.virtualized-scroll-pane :as fx.virtualized-scroll-pane]
    [spring-lobby.util :as u]
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
        console-message-draft (fx/sub-val context get-in [:by-server server-key :console-message-draft])]
    {:fx/type :v-box
     :children
     [
      {:fx/type ext-recreate-on-key-changed
       :key {:server-key server-key}
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
         :text (str console-message-draft)
         :on-text-changed {:event/type :spring-lobby/assoc-in
                           :path [:by-server server-key :console-message-draft]}
         :on-action {:event/type :spring-lobby/send-console
                     :client-data client-data
                     :message console-message-draft
                     :server-key server-key}}]}]}))

(defn console-view
  [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :console-view
      (console-view-impl state))))
