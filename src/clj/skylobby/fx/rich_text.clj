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
    (org.fxmisc.richtext InlineCssTextArea StyleClassedTextArea)
    (org.fxmisc.richtext.model StyledDocument)))


(set! *warn-on-reflection* true)


(def auto-scroll-threshold 80)


(def props
  (merge
    fx.region/props
    (composite/props StyleClassedTextArea
      :document (prop/make
                  (mutator/setter
                    (fn [^StyleClassedTextArea area ^StyledDocument document]
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
                      (let [^StyleClassedTextArea area instance
                            [lines document-fn] value
                            length (.getLength area)]
                        (when-let [^StyledDocument document (document-fn lines)]
                          (.replace area 0 length document))
                        (when-let [^VirtualizedScrollPane parent (.getParent area)]
                          (when (instance? VirtualizedScrollPane parent)
                            (let [^VirtualizedScrollPane scroll-pane parent]
                              (.scrollYBy scroll-pane ##Inf))))))
                    (replace! [_ instance _ old-value new-value]
                      (let [^StyleClassedTextArea area instance
                            [old-lines _old-document-fn] old-value
                            [new-lines new-document-fn auto-scroll] new-value
                            diff-lines (drop (count old-lines) new-lines)]
                        (when (seq diff-lines)
                          (let [index-range (.getSelection area)]
                            (.appendText area "\n")
                            (.append area (new-document-fn diff-lines))
                            (when auto-scroll
                              (let [^VirtualizedScrollPane parent (.getParent area)]
                                (.scrollYBy parent ##Inf)))
                            (.selectRange area (.getStart index-range) (.getEnd index-range))))))
                    (retract! [_ instance _ _]
                      (let [^StyleClassedTextArea area instance]
                        (.deleteText area 0 (.getLength area)))))
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
                    (fn [^InlineCssTextArea area ^StyledDocument document]
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
    :props props-inline))
