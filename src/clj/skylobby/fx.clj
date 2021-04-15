(ns skylobby.fx
  (:require
    [spring-lobby.fs :as fs]))


(def monospace-font-family
  (if (fs/windows?)
    "Consolas"
    "monospace"))

