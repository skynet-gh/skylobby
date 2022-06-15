(ns skylobby.fx.sync
  (:require
    [cljfx.ext.node :as fx.ext.node]
    skylobby.fx
    [skylobby.fx.font-icon :as font-icon]
    [skylobby.fx.tooltip-nofocus :as tooltip-nofocus]
    [skylobby.fs :as fs]))


(set! *warn-on-reflection* true)


(def ok-green "#008000")
(def warn-yellow "#FFD700")
(def error-red "#DD0000")
(def ok-severity
  {:-fx-base ok-green
   :-fx-background ok-green
   :-fx-background-color ok-green})
(def warn-severity
  {:-fx-base warn-yellow
   :-fx-background warn-yellow
   :-fx-background-color warn-yellow})
(def error-severity
  {:-fx-base error-red
   :-fx-background error-red
   :-fx-background-color error-red})
(def severity-styles
  {0 ok-severity
   1 warn-severity
   2 error-severity})

(defn sync-pane
  [{:keys [in-progress issues resource]}]
  (let [worst-severity (reduce
                         (fn [worst {:keys [severity]}]
                           ((fnil max -1 -1) worst severity))
                         -1
                         issues)
        overall-in-progress (or in-progress
                                (some :in-progress issues))
        overall-severity (if overall-in-progress
                           (min 1 worst-severity)
                           worst-severity)]
    {:fx/type :v-box
     :style (merge
              (get severity-styles overall-severity)
              {:-fx-background-radius 3
               :-fx-border-color "#666666"
               :-fx-border-radius 3
               :-fx-border-style "solid"
               :-fx-border-width 1
               :-fx-pref-width 400})
     :children
     (concat
       [{:fx/type :label
         :text (str resource
                    (if (zero? worst-severity) " synced"
                      " status:"))
         :style {:-fx-font-size 16}}]
       (mapv
         (fn [{:keys [action choice choices force-action human-text in-progress on-choice-changed severity text tooltip] :or {severity 2}}]
           (let [font-style {:-fx-font-size 12}
                 display-text (or human-text
                                  (str text " " resource))
                 issue-severity (if overall-in-progress
                                  overall-severity
                                  severity)]
             {:fx/type fx.ext.node/with-tooltip-props
              :props
              (when tooltip
                {:tooltip
                 {:fx/type tooltip-nofocus/lifecycle
                  :show-delay skylobby.fx/tooltip-show-delay
                  :style {:-fx-font-size 14}
                  :text tooltip}})
              :desc
              (if (or (and (zero? severity)
                           (not force-action))
                      (not action))
                (if (< 1 (count choices))
                  {:fx/type :combo-box
                   :value choice
                   :items choices
                   :on-value-changed on-choice-changed
                   :button-cell
                   (fn [file]
                     {:text (str (fs/filename file))})}
                  {:fx/type :label
                   :text display-text
                   :style font-style
                   :graphic
                   {:fx/type font-icon/lifecycle
                    :icon-literal
                    (str "mdi-"
                         (if (zero? severity)
                           "check"
                           (if (= -1 severity)
                             "sync"
                             "exclamation"))
                         ":16:"
                         (if (= 1 overall-severity)
                           "black"
                           "white"))}})
                (let [style (get severity-styles issue-severity)]
                  {:fx/type :v-box
                   :style (merge style font-style)
                   :children
                   [(merge
                      {:fx/type :button
                       :v-box/margin 8
                       :text display-text
                       :disable (boolean in-progress)
                       :on-action action}
                      (when style
                        {:style (dissoc style :-fx-background-color)}))]}))}))
         issues))}))
