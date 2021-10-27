(ns skylobby.fx.ext
  (:require
    [cljfx.api :as fx]
    [cljfx.component :as fx.component]
    [cljfx.lifecycle :as fx.lifecycle]
    [cljfx.mutator :as fx.mutator]
    [cljfx.prop :as fx.prop])
  (:import
    (javafx.beans.value ChangeListener)
    (javafx.scene Node)
    (javafx.scene.control ScrollPane TableView TextArea)
    (org.fxmisc.flowless VirtualizedScrollPane)))


(set! *warn-on-reflection* true)


; https://github.com/cljfx/cljfx/issues/76#issuecomment-645563116
(def ext-recreate-on-key-changed
  "Extension lifecycle that recreates its component when lifecycle's key is changed

  Supported keys:
  - `:key` (required) - a value that determines if returned component should be recreated
  - `:desc` (required) - a component description with additional lifecycle semantics"
  (reify fx.lifecycle/Lifecycle
    (create [_ {:keys [key desc]} opts]
      (with-meta {:key key
                  :child (fx.lifecycle/create fx.lifecycle/dynamic desc opts)}
                 {`fx.component/instance #(-> % :child fx.component/instance)}))
    (advance [this component {:keys [key desc] :as this-desc} opts]
      (if (= (:key component) key)
        (update component :child #(fx.lifecycle/advance fx.lifecycle/dynamic % desc opts))
        (do (fx.lifecycle/delete this component opts)
            (fx.lifecycle/create this this-desc opts))))
    (delete [_ component opts]
      (fx.lifecycle/delete fx.lifecycle/dynamic (:child component) opts))))

; https://github.com/cljfx/cljfx/issues/51#issuecomment-583974585
(def with-scroll-text-prop
  (fx.lifecycle/make-ext-with-props
   fx.lifecycle/dynamic
   {:scroll-text (fx.prop/make
                   (fx.mutator/setter
                     (fn [^TextArea text-area [txt auto-scroll]]
                       (let [scroll-pos (if auto-scroll
                                          ##Inf
                                          (.getScrollTop text-area))]
                         (doto text-area
                           (.setText txt)
                           (some-> .getParent .layout)
                           (.setScrollTop scroll-pos)))))
                   fx.lifecycle/scalar
                   :default ["" true])}))

(def with-scroll-text-flow-prop
  (fx.lifecycle/make-ext-with-props
   fx.lifecycle/dynamic
   {:auto-scroll (fx.prop/make
                   (fx.mutator/setter
                     (fn [^ScrollPane scroll-pane [_texts auto-scroll]]
                       (let [scroll-pos (if auto-scroll
                                          ##Inf
                                          (.getVvalue scroll-pane))]
                         (when auto-scroll
                           (doto scroll-pane
                             (some-> .getParent .layout)
                             (.setVvalue scroll-pos))))))
                   fx.lifecycle/scalar
                   :default [[] true])}))

; figure out layout on create
(defn ext-scroll-on-create [{:keys [desc]}]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created (fn [^VirtualizedScrollPane scroll-pane]
                 (.layout scroll-pane)
                 (.scrollYBy scroll-pane ##Inf)
                 (let [
                       scroll-on-change (reify javafx.beans.value.ChangeListener
                                          (changed [this _observable _old-value _new-value]
                                            (.layout scroll-pane)
                                            (.scrollYBy scroll-pane ##Inf)))
                       width-property (.widthProperty scroll-pane)
                       height-property (.heightProperty scroll-pane)]
                   (.addListener width-property scroll-on-change)
                   (.addListener height-property scroll-on-change)))
   :desc desc})


(defn fix-table-columns [^TableView table-view]
  (when table-view
    (when (seq (.getItems table-view))
      (when-let [column (first (.getColumns table-view))]
        (.resizeColumn table-view column -100.0)
        (.resizeColumn table-view column 100.0)))))

(def with-layout-on-items-prop
  (fx.lifecycle/make-ext-with-props
   fx.lifecycle/dynamic
   {:items (fx.prop/make
             (fx.mutator/setter
               (fn [^TableView table-view ^java.util.Collection items]
                 (let [table-items (.getItems table-view)]
                   (.clear table-items)
                   (when items
                     (.setAll table-items items))
                   (fix-table-columns table-view)
                   (.sort table-view))))
             fx.lifecycle/scalar
             :default [])}))

(defn ext-table-column-auto-size [{:keys [desc items]}]
  {:fx/type with-layout-on-items-prop
   :props {:items items}
   :desc desc})


; https://github.com/cljfx/cljfx/issues/94#issuecomment-691708477
(defn- focus-when-on-scene! [^Node node]
  (if (some? (.getScene node))
    (.requestFocus node)
    (.addListener (.sceneProperty node)
                  (reify ChangeListener
                    (changed [this _ _ new-scene]
                      (when (some? new-scene)
                        (.removeListener (.sceneProperty node) this)
                        (.requestFocus node)))))))

(defn ext-focused-by-default [{:keys [desc]}]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created focus-when-on-scene!
   :desc desc})
