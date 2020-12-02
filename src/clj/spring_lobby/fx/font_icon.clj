(ns spring-lobby.fx.font-icon
  (:require
    [cljfx.composite :as composite]
    [cljfx.lifecycle :as lifecycle])
  (:import
    (org.kordamp.ikonli.javafx FontIcon)))


(set! *warn-on-reflection* true)


(def props
  (composite/props FontIcon
    :icon-literal [:setter lifecycle/scalar]))

(def lifecycle
  (composite/describe FontIcon
    :ctor []
    :props props))
