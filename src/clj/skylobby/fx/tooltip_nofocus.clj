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
      ([^javafx.stage.Window owner]
       (when (.isFocused owner)
         (let [^Tooltip this this]
           (proxy-super show owner))))
      ([owner ^double x ^double y]
       (let [^Tooltip this this]
         (if (instance? javafx.scene.Node owner)
           (let [^javafx.scene.Node owner owner]
             (when (.isFocused owner)
               (proxy-super show owner x y)))
           (let [^javafx.stage.Window owner owner]
             (when (.isFocused owner)
               (proxy-super show owner x y)))))))))

(def lifecycle
  (composite/lifecycle
    {:props fx.tooltip/props
     :args []
     :ctor create}))
