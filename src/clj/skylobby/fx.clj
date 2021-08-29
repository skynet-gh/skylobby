(ns skylobby.fx
  (:require
    [cljfx.css :as css]
    [clojure.java.io :as io]
    [spring-lobby.fs :as fs])
  (:import
    (javafx.stage Screen)))


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
     {:-fx-fill "white"}}
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


(defn screen-bounds []
  (let [screen (Screen/getPrimary)
        bounds (.getVisualBounds screen)]
    {:min-x (.getMinX bounds)
     :min-y (.getMinY bounds)
     :width (.getWidth bounds)
     :height (.getHeight bounds)}))
