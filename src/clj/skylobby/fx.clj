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
    :-fx-background-color "rgb(0, 0, 0, 255)" ; tooltips
    :-fx-control-inner-background "rgb(50, 50, 50)"}
   ".text-field"
   {:-fx-prompt-text-fill "rgb(180, 180, 180)"}})
(def black-style-data
  {".root"
   {:-fx-base "rgb(20, 20, 20)"
    :-fx-accent "rgb(50, 50, 50)"
    :-fx-background "rgb(0, 0, 0)"
    :-fx-background-color "rgb(0, 0, 0, 255)" ; tooltips
    :-fx-control-inner-background "rgb(30, 30, 30)"}
   ".text-field"
   {:-fx-prompt-text-fill "rgb(180, 180, 180)"}})


(def default-style
  (css/register ::default default-style-data))


(defn stylesheet-urls [css]
  [(str (::css/url (or css default-style)))])


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
