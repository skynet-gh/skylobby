(ns skylobby.fx.virtualized-scroll-pane
  (:require
    [cljfx.coerce :as coerce]
    [cljfx.composite :as composite]
    [cljfx.fx.region :as fx.region]
    [cljfx.lifecycle :as lifecycle])
  (:import
    (javafx.scene.control ScrollPane$ScrollBarPolicy)
    (org.fxmisc.flowless VirtualizedScrollPane)))


(set! *warn-on-reflection* true)

(def props
  (merge
    fx.region/props
    (composite/props VirtualizedScrollPane
      :content [:setter lifecycle/dynamic]
      :hbar-policy [:setter lifecycle/scalar
                    :coerce (coerce/enum ScrollPane$ScrollBarPolicy)
                    :default :as-needed]
      :vbar-policy [:setter lifecycle/scalar
                    :coerce (coerce/enum ScrollPane$ScrollBarPolicy)
                    :default :as-needed])))

(def lifecycle
  (composite/describe VirtualizedScrollPane
    :ctor [:content]
    :props props))
