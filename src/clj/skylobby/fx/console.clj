(ns skylobby.fx.console
  (:require
    [cljfx.api :as fx]
    skylobby.fx
    [skylobby.fx.ext :refer [
                             ext-focused-by-default
                             ext-recreate-on-key-changed
                             ext-scroll-on-create]]
    [skylobby.fx.rich-text :as fx.rich-text]
    [skylobby.fx.virtualized-scroll-pane :as fx.virtualized-scroll-pane]
    [skylobby.util :as u]
    [taoensso.tufte :as tufte])
  (:import
    (javafx.scene.control TextField)
    (org.fxmisc.richtext.model ReadOnlyStyledDocumentBuilder SegmentOps StyledSegment)))


(set! *warn-on-reflection* true)


(def default-font-size 18)


(def max-history 1000)


(defn segment
  [text style]
  (StyledSegment. text style))

(defn console-document [all-console-log]
  (let [
        console-log (take-last max-history all-console-log)
        builder (ReadOnlyStyledDocumentBuilder. (SegmentOps/styledTextOps) "")]
    (when-not (= (count all-console-log)
                 (count console-log))
      (.addParagraph builder
        ^java.util.List
        (vec [(segment (str "< " (- (count all-console-log) (count console-log)) " previous messages >") ["text" "skylobby-console-message"])])
        ^java.util.List
        []))
    (doseq [log console-log]
      (let [{:keys [message source timestamp]} log]
        (.addParagraph builder
          ^java.util.List
          (vec
            (concat
              (when timestamp
                [
                 (segment
                   (str "[" (u/format-hours timestamp) "]")
                   ["text" "skylobby-console-time"])])
              (when source
                [(segment
                   (str
                     (case source
                       :server " < "
                       :client " > "
                       " "))
                   ["text" (str "skylobby-console-source-" (name source))])])
              [(segment
                 (str message)
                 ["text" "skylobby-console-message"])]))
          ^java.util.List
          [])))
    (when-not (seq console-log)
      (.addParagraph builder
        ^java.util.List
        (vec [(segment "< no messages >" ["text" "skylobby-console-message"])])
        ^java.util.List
        []))
    (.build builder)))


(defn message-types-sub [context server-key]
  (let [
        console-log (fx/sub-val context get-in [:by-server server-key :console-log])
        message-types (->> console-log
                           (map :message-type)
                           (filter some?)
                           set
                           sort
                           doall)]
    message-types))

(defn console-log-sub [context server-key]
  (let [
        console-log (fx/sub-val context get-in [:by-server server-key :console-log])
        console-ignore-message-types (or (fx/sub-val context :console-ignore-message-types)
                                         {})]
    (->> console-log
         (remove
           (fn [{:keys [message-type]}]
             (get console-ignore-message-types message-type)))
         doall)))

(defn active-console-ignore-message-types-sub [context]
  (let [
        console-ignore-message-types (or (fx/sub-val context :console-ignore-message-types)
                                         {})]
    (set (map first (filter second console-ignore-message-types)))))


(defn console-view-impl
  [{:fx/keys [context]
    :keys [server-key]}]
  (let [
        selected-tab-main (fx/sub-val context get-in [:selected-tab-main server-key])]
    {:fx/type :h-box
     :children
     (if (= "console" selected-tab-main)
       (let [
             client-data (fx/sub-val context get-in [:by-server server-key :client-data])
             console-auto-scroll (fx/sub-val context :console-auto-scroll)
             console-message-draft (fx/sub-val context get-in [:by-server server-key :console-message-draft])
             console-history-index (fx/sub-val context get-in [:by-server server-key :console-history-index])
             console-ignore-message-types (or (fx/sub-val context :console-ignore-message-types)
                                              {})
             message-types (fx/sub-ctx context message-types-sub server-key)
             console-log (fx/sub-ctx context console-log-sub server-key)]
         [{:fx/type :v-box
           :h-box/hgrow :always
           :children
           [
            {:fx/type ext-recreate-on-key-changed
             :key {:server-key server-key
                   :ignore (fx/sub-ctx context active-console-ignore-message-types-sub)}
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
              {:fx/type ext-recreate-on-key-changed
               :h-box/hgrow :always
               :key (str console-history-index)
               :desc
               {:fx/type ext-focused-by-default
                :desc
                {:fx/type fx/ext-on-instance-lifecycle
                 :on-created (fn [^TextField text-field]
                               (.positionCaret text-field (count console-message-draft)))
                 :desc
                 {:fx/type :text-field
                  :id "console-text-field"
                  :text (str console-message-draft)
                  :on-text-changed {:event/type :spring-lobby/assoc-in
                                    :path [:by-server server-key :console-message-draft]}
                  :on-action {:event/type :spring-lobby/send-console
                              :client-data client-data
                              :message console-message-draft
                              :server-key server-key}
                  :on-key-pressed {:event/type :spring-lobby/on-console-key-pressed
                                   :server-key server-key}}}}}]}]}
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
                           :value {}}}]}]}])
       [])}))

(defn console-view
  [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :console-view
      (console-view-impl state))))
