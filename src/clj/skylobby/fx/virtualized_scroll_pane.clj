(ns skylobby.fx.virtualized-scroll-pane
  (:require
    [cljfx.coerce :as coerce]
    [cljfx.composite :as composite]
    [cljfx.mutator :as mutator]
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
      :content [mutator/forbidden lifecycle/dynamic]
      :hbar-policy [:setter lifecycle/scalar
                    :coerce (coerce/enum ScrollPane$ScrollBarPolicy)
                    :default :as-needed]
      :vbar-policy [:setter lifecycle/scalar
                    :coerce (coerce/enum ScrollPane$ScrollBarPolicy)
                    :default :as-needed])))

(def lifecycle
  (composite/lifecycle
    {:props props
     :args [:content]
     :ctor (fn [content]
             (VirtualizedScrollPane. content))}))
