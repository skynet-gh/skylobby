(ns skylobby.fx
  (:require
    [cljfx.api :as fx]
    [cljfx.css :as css]
    [clojure.java.io :as io]
    [skylobby.fs :as fs]
    [skylobby.resource :as resource]
    [skylobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (javafx.stage Screen)
    (org.apache.commons.collections4 ComparatorUtils)))


(set! *warn-on-reflection* true)


(def tooltip-show-delay [200 :ms])
(def tooltip-show-delay-slow [500 :ms]) ; some things are jarring otherwise


(def case-insensitive-natural-comparator
  (.thenComparing
    String/CASE_INSENSITIVE_ORDER
    ComparatorUtils/NATURAL_COMPARATOR))


(def divider-positions
  (atom {}))
(def window-states
  (atom {}))

(defn window-changed [window k v]
  (swap! window-states update window
    (fn [window-state]
      (if (and (:maximized window-state)
               (not= :maximized k))
        window-state ; don't save size if maximized
        (assoc window-state k v)))))

(defn add-maximized-listener [window-key ^javafx.stage.Stage node]
  (let [maximized-property (.maximizedProperty node)]
    (.addListener maximized-property
      (reify javafx.beans.value.ChangeListener
        (changed [_this _observable _old-value new-value]
          (window-changed window-key :maximized new-value))))))

(defn add-divider-listener [^javafx.scene.control.SplitPane node divider-key]
  (let [dividers (.getDividers node)]
    (when-let [^javafx.scene.control.SplitPane$Divider divider (first dividers)]
      (.addListener (.positionProperty divider)
        (reify javafx.beans.value.ChangeListener
          (changed [_this _observable _old-value new-value]
            (swap! divider-positions assoc divider-key new-value)))))))

(def monospace-font-family
  (if (fs/windows?)
    "Consolas"
    "monospace"))


; https://tomsondev.bestsolution.at/2014/03/13/eclipse-on-javafx-get-dark-the-power-of-javafx-css
(def grey-style-data
  {".root"
   {:-fx-base "rgb(50, 50, 50)"
    :-fx-accent "rgb(80, 80, 80)"
    :-fx-background "rgb(50, 50, 50)"
    ;:-fx-background-color "rgb(0, 0, 0, 255)" ; tooltips
    :-fx-control-inner-background "rgb(50, 50, 50)"
    :-fx-selection-bar-non-focused "rgb(60, 60, 60)"}
   ".styled-text-area"
   {:-fx-background-color "rgb(50, 50, 50)"}
   ".text-field"
   {:-fx-prompt-text-fill "rgb(180, 180, 180)"}
   ".skylobby"
   {"-normal"
    {"> .ikonli-font-icon"
     {:-fx-icon-color "white"}}
    "-tab"
    {"-focus"
     {:-fx-background "#ffd700"
      :-fx-base "#ffd700"}}}})
(def black-style-data
  {".root"
   {:-fx-base "rgb(20, 20, 20)"
    :-fx-accent "rgb(50, 50, 50)"
    :-fx-background "rgb(0, 0, 0)"
    :-fx-control-inner-background "rgb(30, 30, 30)"
    :-fx-selection-bar-non-focused "rgb(40, 40, 40)"}
   ".tab"
   {:-fx-base "rgb(35, 35, 35)"
    :-fx-background "rgb(40, 40, 40)"
    :-fx-accent "rgb(0, 0, 0)"}
   ".tab:selected"
   {:-fx-background "rgb(0, 0, 0)"
    :-fx-accent "rgb(160, 160, 160)"}
   ".styled-text-area"
   {:-fx-background-color "rgb(0, 0, 0)"}
   ".text-field"
   {:-fx-prompt-text-fill "rgb(180, 180, 180)"}
   ".skylobby"
   {"-normal"
    {"> .ikonli-font-icon"
     {:-fx-icon-color "white"}}
    "-chat"
    {"-user-list"
     {:-fx-text-fill "lightgrey"}}
    "-tab"
    {"-focus"
     {:-fx-background "#ffd700"
      :-fx-base "#ffd700"}}}})
(def javafx-style-data
  {
   ".styled-text-area"
   {:-fx-background-color "rgb(255, 255, 255)"}
   ".skylobby"
   {"-normal"
    {"> .ikonli-font-icon"
     {:-fx-icon-color "dimgrey"}}
    "-chat"
    {
     "-message"
     {:-fx-fill "black"}
     "-user-list"
     {:-fx-text-fill "black"}}
    "-console"
    {
     "-message"
     {:-fx-fill "black"}}
    "-tab"
    {"-focus"
     {:-fx-background "#ffd700"
      :-fx-base "#ffd700"}}}})
(def default-style-data black-style-data)


(def style-presets
  {
   "black" black-style-data
   "grey" grey-style-data
   "javafx" javafx-style-data})


(def default-style
  (css/register ::default black-style-data))

; so that themes can override
(def default-classes
  {
   ".styled-text-area"
   {:-fx-background-color "rgb(50, 50, 50)"}
   ".hyperlink"
   {:-fx-text-fill "rgb(128, 128, 128)"}
   ".skylobby"
   {"-normal"
    {"> .ikonli-font-icon"
     {:-fx-icon-color "dimgrey"}}
    "-players"
    {"-multiplayer"
     {:-fx-font-size 14
      "-nickname"
      {:-fx-font-size 16}}
     "-replay"
     {:-fx-font-size 14
      "-nickname"
      {:-fx-font-size 16}}
     "-singleplayer"
     {:-fx-font-size 20
      "-nickname"
      {:-fx-font-size 22}}}
    "-tab"
    {"-focus"
     {:-fx-background "#ffd700"
      :-fx-base "#ffd700"}}
    "-chat"
    {
     "-info"
     {:-fx-fill "grey"}
     "-time"
     {:-fx-fill "grey"}
     "-username"
     {:-fx-fill "royalblue"}
     "-username-me"
     {:-fx-fill "orange"}
     "-username-ex"
     {:-fx-fill "cyan"}
     "-username-join"
     {:-fx-fill "grey"}
     "-username-leave"
     {:-fx-fill "grey"}
     "-message"
     {:-fx-fill "white"}
     "-message-ex"
     {:-fx-fill "cyan"}
     "-message-url"
     {; https://stackoverflow.com/a/4774037
      :-fx-fill "#0000EE"
      :-fx-cursor "hand"
      :-fx-underline true}
     "-message-highlight"
     {:-fx-fill "red"}
     "-message-irc-00"
     {:-fx-fill "rgb(255,255,255)"}
     "-message-irc-01"
     {:-fx-fill "rgb(255,255,255)"}
     "-message-irc-02"
     {:-fx-fill "rgb(0,0,127)"}
     "-message-irc-03"
     {:-fx-fill "rgb(0,147,0)"}
     "-message-irc-04"
     {:-fx-fill "rgb(255,0,0)"}
     "-message-irc-05"
     {:-fx-fill "rgb(127,0,0)"}
     "-message-irc-06"
     {:-fx-fill "rgb(156,0,156)"}
     "-message-irc-07"
     {:-fx-fill "rgb(252,127,0)"}
     "-message-irc-08"
     {:-fx-fill "rgb(255,255,0)"}
     "-message-irc-09"
     {:-fx-fill "rgb(0,252,0)"}
     "-message-irc-10"
     {:-fx-fill "rgb(0,147,147)"}
     "-message-irc-11"
     {:-fx-fill "rgb(0,255,255)"}
     "-message-irc-12"
     {:-fx-fill "rgb(0,0,252)"}
     "-message-irc-13"
     {:-fx-fill "rgb(255,0,255)"}
     "-message-irc-14"
     {:-fx-fill "rgb(127,127,127)"}
     "-message-irc-15"
     {:-fx-fill "rgb(210,210,210)"}
     "-input"
     {:-fx-font-size 15}}
    "-console"
    {
     "-time"
     {:-fx-fill "grey"}
     "-source-server"
     {:-fx-fill "goldenrod"}
     "-source-client"
     {:-fx-fill "royalblue"}
     "-message"
     {:-fx-fill "white"}}
    "-spring-log"
    {
     "-err"
     {:-fx-fill "red"}
     "-out"
     {:-fx-fill "white"}}}})

(def default-classes-css
  (css/register ::default-classes default-classes))

(defn stylesheet-urls [css]
  [
   (str (::css/url default-classes-css))
   (str (::css/url (or css default-style)))])

(defn stylesheet-urls-sub [context]
  (stylesheet-urls (fx/sub-val context :css)))

(defn all-tasks-sub [context]
  (->> (fx/sub-val context :tasks-by-kind)
       (mapcat second)
       (concat (vals (fx/sub-val context :current-tasks)))
       (filter some?)
       doall))

(defn tasks-by-type-sub [context]
  (group-by :spring-lobby/task-type (fx/sub-ctx context all-tasks-sub)))

(defn tasks-of-type-sub [context task-type]
  (->> (fx/sub-ctx context all-tasks-sub)
       (filterv (comp #{task-type} :spring-lobby/task-type))))

(defn valid-servers-sub [context]
  (u/valid-servers (fx/sub-val context :by-server)))

(defn valid-server-keys-sub [context]
  (u/valid-server-keys (fx/sub-val context :by-server)))


(defn welcome-server-key-sub [context]
  (u/server-key {:server-url (fx/sub-val context (comp first :server))
                 :username (fx/sub-val context :username)}))

(defn selected-server-data-sub [context]
  (get (fx/sub-val context :by-server)
       (u/server-key {:server-url (first (fx/sub-val context :server))
                      :username (fx/sub-val context :username)})))

(defn selected-tab-server-key-sub [context]
  (let [selected-server-tab (fx/sub-val context :selected-server-tab)
        by-server-keys (fx/sub-val context (comp keys :by-server))]
    (or (->> by-server-keys
             (filter #{selected-server-tab})
             first)
        :local)))

(defn auto-servers-sub [context]
  (->> (fx/sub-val context :servers)
       (filterv (comp :auto-connect second))))

(defn server-key-set-sub [context]
  (fx/sub-val context (comp set keys :by-server)))

(defn auto-servers-not-connected-sub [context]
  (let [server-keys (fx/sub-ctx context server-key-set-sub)
        logins (fx/sub-val context :logins)]
    (->> (fx/sub-ctx context auto-servers-sub)
         (filter (comp :auto-connect second))
         (map (fn [[server-url _server-data]]
                (u/server-key
                  {:server-url server-url
                   :username (-> logins (get server-url) :username)})))
         (filter some?)
         (remove (fn [server-key] (contains? server-keys server-key)))
         doall)))


(defn map-details-sub [context map-key]
  (resource/cached-details (fx/sub-val context :map-details) map-key))

(defn mod-details-sub [context mod-key]
  (resource/cached-details (fx/sub-val context :mod-details) mod-key))

(defn replay-details-sub [context k]
  (get (fx/sub-val context :replay-details) k))

(defn spring-root-sub [context server-url]
  (let [servers (fx/sub-val context :servers)]
    (or (-> servers (get server-url) :spring-isolation-dir)
        (fx/sub-val context :spring-isolation-dir))))

(defn spring-root-resources-sub [context server-url]
  (let [spring-root (fx/sub-ctx context spring-root-sub server-url)]
    (resource/spring-root-resources spring-root (fx/sub-val context :by-spring-root))))

(defn spring-resources-sub [context spring-root]
  (resource/spring-root-resources spring-root (fx/sub-val context :by-spring-root)))

(defn selected-replay-sub [context]
  (or (get (fx/sub-val context :parsed-replays-by-path) (fs/canonical-path (fx/sub-val context :selected-replay-file)))
      (get (fx/sub-val context :online-bar-replays) (fx/sub-val context :selected-replay-id))))

(defn battle-channel-sub
  ([context server-key]
   (battle-channel-sub context server-key (fx/sub-val context get-in [:by-server server-key :battle :battle-id])))
  ([context server-key battle-id]
   (let [
         channel-name (fx/sub-val context get-in [:by-server server-key :battles battle-id :channel-name])]
     (or channel-name
         (str "__battle__" battle-id)))))


(def icons
  [(str (io/resource "icon16.png"))
   (str (io/resource "icon32.png"))
   (str (io/resource "icon48.png"))])
   ;(str (io/resource "icon64.png"))])
   ;(str (io/resource "icon128.png"))
   ;(str (io/resource "icon256.png"))
   ;(str (io/resource "icon512.png"))
   ;(str (io/resource "icon1024.png"))])


(def min-width 256)
(def min-height 256)


(defn get-screen-bounds []
  (let [screens (Screen/getScreens)
        xy (reduce
             (fn [{:keys [min-x min-y max-x max-y]} ^javafx.stage.Screen screen]
               (let [bounds (.getVisualBounds screen)]
                 {:min-x (if min-x (min (.getMinX bounds) min-x) (.getMinX bounds))
                  :min-y (if min-y (min (.getMinY bounds) min-y) (.getMinY bounds))
                  :max-x (if max-x (max (.getMaxX bounds) max-x) (.getMaxX bounds))
                  :max-y (if max-y (max (.getMaxY bounds) max-y) (.getMaxY bounds))}))
             {}
             screens)]
    (assoc xy
           :width (if (and (:min-x xy) (:max-x xy))
                    (- (:max-x xy) (:min-x xy))
                    min-width)
           :height (if (and (:min-y xy) (:max-y xy))
                     (- (:max-y xy) (:min-y xy))
                     min-height))))

(defn screen-bounds-fallback [screen-bounds]
  (or screen-bounds
      (do
        (log/warn (ex-info "stacktrace" {}) "No screen-bounds, performance issue here")
        (get-screen-bounds))))

(defn fitx
  ([screen-bounds]
   (fitx screen-bounds nil))
  ([screen-bounds setting]
   (let [screen-bounds (screen-bounds-fallback screen-bounds)]
     (max
       (min
         (or setting
             (when (and (:min-x screen-bounds) (:max-x screen-bounds))
               (quot (+ (:min-x screen-bounds)
                        (- (:max-x screen-bounds) min-width))
                     2))
             Integer/MIN_VALUE)
         (or (:max-x screen-bounds) 0))
       (or (:min-x screen-bounds) 0)))))

(defn fity
  ([screen-bounds]
   (fity screen-bounds nil))
  ([screen-bounds setting]
   (let [screen-bounds (screen-bounds-fallback screen-bounds)]
     (max
       (min
         (or setting
             (when (and (:min-y screen-bounds) (:max-y screen-bounds))
               (quot (+ (:min-y screen-bounds)
                        (- (:max-y screen-bounds) min-height))
                     2))
             Integer/MIN_VALUE)
         (or (:max-y screen-bounds) 0))
       (or (:min-y screen-bounds) 0)))))

(defn fitwidth
  ([screen-bounds default]
   (fitwidth screen-bounds nil default))
  ([screen-bounds setting default]
   (let [screen-bounds (screen-bounds-fallback screen-bounds)]
     (max
       (min
         (or setting default min-width)
         (or (:width screen-bounds) default))
       min-width))))

(defn fitheight
  ([screen-bounds default]
   (fitheight screen-bounds nil default))
  ([screen-bounds setting default]
   (let [screen-bounds (screen-bounds-fallback screen-bounds)]
     (max
       (min
         (or setting default min-height)
         (or (:height screen-bounds) default))
       min-height))))
