(ns skylobby.fx.pick-spring-root
  (:require
    [cljfx.api :as fx]
    skylobby.fx
    [skylobby.fs :as fs]
    [skylobby.fx.bottom-bar :as fx.bottom-bar]
    [skylobby.fx.font-icon :as font-icon]
    [skylobby.fx.sub :as sub]
    [skylobby.fx.sync :refer [ok-severity]])
  (:import
    (javafx.scene.input Clipboard ClipboardContent)))


(set! *warn-on-reflection* true)


(defn pick-spring-root-view
  [{:fx/keys [context]}]
  (let [spring-isolation-dir (fx/sub-val context :spring-isolation-dir)
        spring-root-path (fs/canonical-path spring-isolation-dir)
        {:keys [engines maps mods]} (fx/sub-ctx context sub/spring-resources spring-isolation-dir)
        default-spring-root (fs/default-spring-root)
        wanted-spring-roots [
                             {:title "Spring"
                              :file (fs/spring-root)}
                             {:title "Beyond All Reason"
                              :file (fs/bar-root)}]]
    {:fx/type :v-box
     :children
     [{:fx/type :pane
       :v-box/vgrow :always}
      {:fx/type :h-box
       :children
       [
        {:fx/type :pane
         :h-box/hgrow :always}
        {:fx/type :scroll-pane
         :fit-to-width true
         :content
         {:fx/type :v-box
          :children
          (concat
            [{:fx/type :label
              :style {:-fx-font-size 28}
              :text "Welcome to skylobby!"}
             {:fx/type :label
              :style {:-fx-font-size 20}
              :text "Spring resources will be saved to the directory below."}
             {:fx/type :label
              :style {:-fx-font-size 20}
              :text "If you already have Spring games elsewhere on your machine, you can change the directory."}
             {:fx/type :label
              :style {:-fx-font-size 16}
              :text "Click the green button to continue, you can always change this in settings later."}
             {:fx/type :pane
              :pref-height 16}
             {:fx/type :h-box
              :alignment :center-left 
              :children
              [
               {:fx/type :label
                :style {:-fx-font-size 16}
                :text "Current Spring directory: "}
               {:fx/type :button
                :style-class ["button" "skylobby-normal"]
                :text ""
                :graphic
                {:fx/type font-icon/lifecycle
                 :icon-literal "mdi-content-copy:22"}
                :tooltip {:fx/type :tooltip
                          :text "Copy path to clipboard"}
                :on-action (fn [_event]
                            (let [clipboard (Clipboard/getSystemClipboard)
                                  content (ClipboardContent.)]
                              (.putString content (str spring-root-path))
                              (.setContent clipboard content)))}
               {:fx/type :button
                :style-class ["button" "skylobby-normal"]
                :text ""
                :graphic
                {:fx/type font-icon/lifecycle
                 :icon-literal "mdi-folder:22"}
                :tooltip {:fx/type :tooltip
                          :text "Open in file browser"}
                :on-action {:event/type :spring-lobby/desktop-browse-dir
                            :file spring-isolation-dir}}]}
             {:fx/type :label
              :style {:-fx-font-size 18}
              :text (str spring-root-path)}
             {:fx/type :pane
              :pref-height 16}
             {:fx/type :h-box
              :style {:-fx-font-size 18}
              :children
              [
               {:fx/type :button
                :on-action {:event/type :spring-lobby/assoc
                            :key :show-spring-picker
                            :value false}
                :style (dissoc ok-severity :-fx-background-color)
                :text "Use this Spring directory"}
               {:fx/type :pane
                :pref-width 16}
               {:fx/type :button
                :disable (= spring-root-path
                            (fs/canonical-path default-spring-root))
                :on-action {:event/type :spring-lobby/assoc
                            :key :spring-isolation-dir
                            :value default-spring-root}
                :text "Reset to default"}]}
             {:fx/type :pane
              :pref-height 16}
             {:fx/type :label
              :style {:-fx-font-size 16}
              :text "Resources found:"}
             {:fx/type :label
              :style {:-fx-font-size 18}
              :text (str "Engine versions: " (count engines))}
             {:fx/type :label
              :style {:-fx-font-size 18}
              :text (str "Game versions: " (count mods))}
             {:fx/type :label
              :style {:-fx-font-size 18}
              :text (str "Maps: " (count maps))}
             {:fx/type :pane
              :pref-height 16}
             {:fx/type :label
              :style {:-fx-font-size 18}
              :text "Alternative Spring directories:"}]
            (mapv
              (fn [{:keys [title file]}]
                {:fx/type :v-box
                 :children
                 [
                  {:fx/type :label
                   :style {:-fx-font-size 22}
                   :text title}
                  {:fx/type :label
                   :style {:-fx-font-size 18}
                   :text (str file)}
                  (if (fs/exists? file)
                    {:fx/type :button
                     :style {:-fx-font-size 18}
                     :disable (= spring-root-path
                                 (fs/canonical-path file))
                     :on-action {:event/type :spring-lobby/assoc
                                 :key :spring-isolation-dir
                                 :value file}
                     :text "Pick this Spring directory"}
                    {:fx/type :label
                     :style {:-fx-text-fill :red}
                     :text "(does not exist)"})]})
              wanted-spring-roots)
            [
             {:fx/type :pane
              :pref-height 16}
             {:fx/type :label
              :style {:-fx-font-size 18}
              :text "Or set a custom directory:"}
             {:fx/type :button
              :style-class ["button" "skylobby-normal"]
              :on-action {:event/type :spring-lobby/file-chooser-dir
                          :initial-dir spring-isolation-dir
                          :path [:spring-isolation-dir]}
              :style {:-fx-font-size 18}
              :text "Pick custom Spring directory"
              :graphic
              {:fx/type font-icon/lifecycle
               :icon-literal "mdi-file-find:16"}}])}}
        {:fx/type :pane
         :h-box/hgrow :always}]}
      {:fx/type :pane
       :v-box/vgrow :always}
      {:fx/type fx.bottom-bar/bottom-bar}]}))
