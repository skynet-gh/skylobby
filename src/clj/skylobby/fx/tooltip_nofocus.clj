(ns skylobby.fx.tooltip-nofocus
  (:require
    [cljfx.composite :as composite]
    [cljfx.fx.tooltip :as fx.tooltip])
  (:import
    (javafx.scene.control Tooltip)))


(set! *warn-on-reflection* true)


; https://stackoverflow.com/a/45468459/984393

(defn create []
  (proxy [Tooltip] []
    (show
      ([owner]
       (when (.isFocused owner)
         (proxy-super show owner)))
      ([owner x y]
       (when (.isFocused owner)
         (proxy-super show owner x y))))))

(def lifecycle
  (composite/lifecycle
    {:props fx.tooltip/props
     :args []
     :ctor create}))
