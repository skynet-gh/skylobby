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

(def ext-with-auto-scroll-virtual-prop
  (fx.lifecycle/make-ext-with-props
   fx.lifecycle/dynamic
   {:auto-scroll (fx.prop/make
                   (fx.mutator/setter
                     (fn [^VirtualizedScrollPane scroll-pane _content]
                       (let [ybar (nth (vec (.getChildrenUnmodifiable scroll-pane)) 2)]
                         (when (> (.getValue ybar) (- (.getMax ybar) 100)) ; TODO fix
                           (.scrollYBy scroll-pane ##Inf)))))
                   fx.lifecycle/scalar
                   :default ["" true])}))

(defn fix-table-columns [^TableView table-view]
  (when table-view
    (when-let [items (.getItems table-view)]
      (when (seq items)
        (when-let [column (first (.getColumns table-view))]
          (.resizeColumn table-view column -100.0)
          (.resizeColumn table-view column 100.0))))))

(def with-layout-on-items-prop
  (fx.lifecycle/make-ext-with-props
   fx.lifecycle/dynamic
   {:items (fx.prop/make
             (fx.mutator/setter
               (fn [^TableView table-view items]
                 (.setAll (.getItems table-view) items)
                 (fix-table-columns table-view)))
             fx.lifecycle/scalar
             :default [])}))

(defn ext-table-column-auto-size [{:keys [desc items]}]
  {:fx/type with-layout-on-items-prop
   :props {:items items}
   :desc desc})


; figure out layout on create
#_
{:fx/type fx/ext-on-instance-lifecycle
 :on-created (fn [^TableView table-view]
               (Platform/runLater (fn [] (fix-table-columns table-view))))
 :desc desc}


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
