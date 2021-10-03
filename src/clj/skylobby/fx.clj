(ns skylobby.fx
  (:require
    [cljfx.css :as css]
    [clojure.java.io :as io]
    [spring-lobby.fs :as fs])
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


(defn screen-bounds []
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

(defn fitx
  ([screen-bounds]
   (fitx screen-bounds nil))
  ([screen-bounds setting]
   (max
     (min
       (or setting Integer/MIN_VALUE)
       (or (:max-x screen-bounds) 0))
     (or (:min-x screen-bounds) 0))))
(defn fity
  ([screen-bounds]
   (fity screen-bounds nil))
  ([screen-bounds setting]
   (max
     (min
       (or setting Integer/MIN_VALUE)
       (or (:max-y screen-bounds) 0))
     (or (:min-y screen-bounds) 0))))
(defn fitwidth
  ([screen-bounds default]
   (fitwidth screen-bounds nil default))
  ([screen-bounds setting default]
   (max
     (min
       (or setting default 0)
       (or (:width screen-bounds) min-width))
     min-width)))
(defn fitheight
  ([screen-bounds default]
   (fitheight screen-bounds nil default))
  ([screen-bounds setting default]
   (max
     (min
       (or setting default 0)
       (or (:height screen-bounds) min-height))
     min-height)))
