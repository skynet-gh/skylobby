(ns skylobby.fx
  (:require
    [cljfx.api :as fx]
    [cljfx.css :as css]
    [clojure.java.io :as io]
    [skylobby.resource :as resource]
    [spring-lobby.fs :as fs]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (javafx.stage Screen)))


(set! *warn-on-reflection* true)


(def divider-positions
  (atom {}))
(def window-states
  (atom {}))

(defn window-changed [window k v]
  (swap! window-states assoc-in [window k] v))

(defn add-maximized-listener [window-key ^javafx.stage.Stage node]
  (let [maximized-property (.maximizedProperty node)]
    (.addListener maximized-property
      (reify javafx.beans.value.ChangeListener
        (changed [this _observable _old-value new-value]
          (window-changed window-key :maximized new-value))))))


(def monospace-font-family
  (if (fs/windows?)
    "Consolas"
    "monospace"))


; https://tomsondev.bestsolution.at/2014/03/13/eclipse-on-javafx-get-dark-the-power-of-javafx-css
(def default-style-data
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
     {:-fx-icon-color "white"}}}})
(def black-style-data
  {".root"
   {:-fx-base "rgb(20, 20, 20)"
    :-fx-accent "rgb(50, 50, 50)"
    :-fx-background "rgb(0, 0, 0)"
    ;:-fx-background-color "rgb(0, 0, 0, 255)" ; tooltips
    :-fx-control-inner-background "rgb(30, 30, 30)"
    :-fx-selection-bar-non-focused "rgb(40, 40, 40)"}
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
     {:-fx-text-fill "lightgrey"}}}})
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
     {:-fx-fill "black"}}}})


(def style-presets
  {"default" default-style-data
   "black" black-style-data
   "javafx" javafx-style-data})


(def default-style
  (css/register ::default default-style-data))

; so that themes can override
(def default-classes
  {
   ".styled-text-area"
   {:-fx-background-color "rgb(50, 50, 50)"}
   ".skylobby"
   {"-normal"
    {"> .ikonli-font-icon"
     {:-fx-icon-color "dimgrey"}}
    "-tab"
    {"-focus"
     {:-fx-background "#ffd700"
      :-fx-base "#ffd700"}}
    "-chat"
    {
     "-time"
     {:-fx-fill "grey"}
     "-username"
     {:-fx-fill "royalblue"}
     "-username-ex"
     {:-fx-fill "cyan"}
     "-username-join"
     {:-fx-fill "grey"}
     "-username-leave"
     {:-fx-fill "grey"}
     "-message"
     {:-fx-fill "white"}
     "-message-highlight"
     {:-fx-fill "red"}}
    "-console"
    {
     "-time"
     {:-fx-fill "grey"}
     "-source-server"
     {:-fx-fill "goldenrod"}
     "-source-client"
     {:-fx-fill "royalblue"}
     "-message"
     {:-fx-fill "white"}}}})
(def default-classes-css
  (css/register ::default default-classes))


(defn stylesheet-urls [css]
  [(str (::css/url default-classes-css))
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
       (filter (comp #{task-type} :spring-lobby/task-type))))

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

(defn battle-channel-sub [context server-key]
  (let [battle-id (fx/sub-val context get-in [:by-server server-key :battle :battle-id])
        channel-name (fx/sub-val context get-in [:by-server server-key :battles battle-id :channel-name])]
    (or channel-name
        (str "__battle__" battle-id))))


(def icons
  [(str (io/resource "icon16.png"))
   (str (io/resource "icon32.png"))
   (str (io/resource "icon64.png"))
   (str (io/resource "icon128.png"))
   (str (io/resource "icon256.png"))
   (str (io/resource "icon512.png"))
   (str (io/resource "icon1024.png"))])


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
         (or setting Integer/MIN_VALUE)
         (or (:max-x screen-bounds) 0))
       (or (:min-x screen-bounds) 0)))))

(defn fity
  ([screen-bounds]
   (fity screen-bounds nil))
  ([screen-bounds setting]
   (let [screen-bounds (screen-bounds-fallback screen-bounds)]
     (max
       (min
         (or setting Integer/MIN_VALUE)
         (or (:max-y screen-bounds) 0))
       (or (:min-y screen-bounds) 0)))))

(defn fitwidth
  ([screen-bounds default]
   (fitwidth screen-bounds nil default))
  ([screen-bounds setting default]
   (let [screen-bounds (screen-bounds-fallback screen-bounds)]
     (max
       (min
         (or setting default 0)
         (or (:width screen-bounds) min-width))
       min-width))))

(defn fitheight
  ([screen-bounds default]
   (fitheight screen-bounds nil default))
  ([screen-bounds setting default]
   (let [screen-bounds (screen-bounds-fallback screen-bounds)]
     (max
       (min
         (or setting default 0)
         (or (:height screen-bounds) min-height))
       min-height))))
