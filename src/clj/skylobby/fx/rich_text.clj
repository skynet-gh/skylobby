(ns skylobby.fx.rich-text
  (:require
    [cljfx.composite :as composite]
    [cljfx.fx.region :as fx.region]
    [cljfx.lifecycle :as lifecycle]
    [cljfx.mutator :as mutator]
    [cljfx.prop :as prop]
    [taoensso.timbre :as log])
  (:import
    (javafx.scene.control IndexRange)
    (org.fxmisc.flowless VirtualizedScrollPane)
    (org.fxmisc.richtext InlineCssTextArea StyleClassedTextArea)))


(set! *warn-on-reflection* true)


(def auto-scroll-threshold 80)


(def props
  (merge
    fx.region/props
    (composite/props StyleClassedTextArea
      :document (prop/make
                  (mutator/setter
                    (fn [area document]
                      (let [
                            length (.getLength area)]
                        (if document
                          (let [
                                new-length (.length document)
                                diff (- new-length length)]
                            (if (pos? diff)
                              (.replace area length length (.subSequence document (IndexRange. length new-length)))
                              (do
                                (log/warn "New document is shorted than old one:" new-length "was" length)
                                (.replace area 0 length document))))
                          (.deleteText area 0 length)))))
                  lifecycle/scalar)
      :editable [:setter lifecycle/scalar :default false]
      :wrap-text [:setter lifecycle/scalar :default true])))

(def lifecycle
  (composite/describe StyleClassedTextArea
    :ctor []
    :props props))


(def props-fast
  (merge
    fx.region/props
    (composite/props StyleClassedTextArea
      :document (prop/make
                  (reify mutator/Mutator
                    (assign! [_ instance _ value]
                      (let [[lines document-fn] value
                            length (.getLength instance)]
                        (when-let [document (document-fn lines)]
                          (.replace instance 0 length document)))
                      (when (and (.getParent instance) (instance? VirtualizedScrollPane (.getParent instance)))
                        (.scrollYBy (.getParent instance) ##Inf)))
                    (replace! [_ instance _ old-value new-value]
                      (let [[old-lines _old-document-fn] old-value
                            [new-lines new-document-fn auto-scroll] new-value
                            diff-lines (drop (count old-lines) new-lines)]
                        (when (seq diff-lines)
                          (let [index-range (.getSelection instance)]
                            (.appendText instance "\n")
                            (.append instance (new-document-fn diff-lines))
                            (when auto-scroll
                              (.scrollYBy (.getParent instance) ##Inf))
                            (.selectRange instance (.getStart index-range) (.getEnd index-range))))))
                    (retract! [_ instance _ _]
                      (.deleteText instance 0 (.getLength instance))))
                  lifecycle/scalar)
      :editable [:setter lifecycle/scalar :default false]
      :wrap-text [:setter lifecycle/scalar :default true])))

(def lifecycle-fast
  (composite/describe StyleClassedTextArea
    :ctor []
    :props props-fast))


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
