(ns skylobby.fx
  (:require
    [clojure.java.io :as io]
    [spring-lobby.fs :as fs]))


(def monospace-font-family
  (if (fs/windows?)
    "Consolas"
    "monospace"))


(def stylesheets
  [(str (io/resource "dark.css"))])


(def icons
  [(str (io/resource "icon16.png"))
   (str (io/resource "icon32.png"))
   (str (io/resource "icon64.png"))
   (str (io/resource "icon128.png"))
   (str (io/resource "icon256.png"))
   (str (io/resource "icon512.png"))
   (str (io/resource "icon1024.png"))])
