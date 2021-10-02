(ns skylobby.fx.text-ext
  (:require
    [cljfx.composite :as composite]
    [cljfx.fx.text :as fx.text])
  (:import
    (org.fxmisc.richtext TextExt)))


(set! *warn-on-reflection* true)


(def props
  (merge
    fx.text/props
    (composite/props TextExt)))

(def lifecycle
  (composite/describe TextExt
    :ctor []
    :props props))
