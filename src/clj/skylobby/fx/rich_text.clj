(ns skylobby.fx.rich-text
  (:require
    [cljfx.composite :as composite]
    [cljfx.fx.region :as fx.region]
    [cljfx.lifecycle :as lifecycle]
    [cljfx.mutator :as mutator]
    [cljfx.prop :as prop])
  (:import
    (org.fxmisc.flowless VirtualizedScrollPane)
    (org.fxmisc.richtext InlineCssTextArea StyleClassedTextArea)
    (org.fxmisc.richtext.model StyledDocument)))


(set! *warn-on-reflection* true)


(def auto-scroll-threshold 80)


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
                          (let [index-range (.getSelection area)
                                select-start (.getStart index-range)
                                select-end (.getEnd index-range)]
                            (.appendText area "\n")
                            (.append area (new-document-fn diff-lines))
                            (if (not= select-start select-end)
                              (.selectRange area select-start select-end)
                              (when auto-scroll
                                (.layout area)
                                (let [last-para-index (max 0 (dec (count (.getParagraphs area))))]
                                  (.showParagraphAtBottom area last-para-index))))))))
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
