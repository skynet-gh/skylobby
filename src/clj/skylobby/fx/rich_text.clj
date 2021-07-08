(ns skylobby.fx.rich-text
  (:require
    [cljfx.composite :as composite]
    [cljfx.fx.region :as fx.region]
    [cljfx.lifecycle :as lifecycle]
    [cljfx.mutator :as mutator]
    [cljfx.prop :as prop])
  (:import
    (org.fxmisc.richtext InlineCssTextArea StyleClassedTextArea)))


(set! *warn-on-reflection* true)


(def props
  (merge
    fx.region/props
    (composite/props StyleClassedTextArea
      :document (prop/make
                  (mutator/setter
                    (fn [area document]
                      (let [length (.getLength area)]
                        (if document
                          (.replace area 0 length document)
                          (.deleteText area 0 length)))))
                  lifecycle/scalar)
      :editable [:setter lifecycle/scalar :default false]
      :wrap-text [:setter lifecycle/scalar :default true])))

(def lifecycle
  (composite/describe StyleClassedTextArea
    :ctor []
    :props props))


(def props-inline
  (merge
    fx.region/props
    (composite/props InlineCssTextArea
      :document (prop/make
                  (mutator/setter
                    (fn [area document]
                      (let [length (.getLength area)]
                        (if document
                          (.replace area 0 length document)
                          (.deleteText area 0 length)))))
                  lifecycle/scalar)
      :editable [:setter lifecycle/scalar :default false]
      :wrap-text [:setter lifecycle/scalar :default true])))

(def lifecycle-inline
  (composite/describe InlineCssTextArea
    :ctor []
    :props props))
