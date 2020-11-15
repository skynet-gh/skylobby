(ns user
  (:require
    [cljfx.api :as fx]
    [clojure.datafy :refer [datafy]]
    [clojure.java.io :as io]
    [clojure.pprint :refer [pprint]]
    [clojure.tools.namespace.repl :refer [disable-unload! disable-reload! refresh]]
    [hawk.core :as hawk]
    [pjstadig.humane-test-output]
    [spring-lobby]))


(disable-unload!)
(disable-reload!)


(def state (atom nil))
(def ^:dynamic renderer nil)


(pjstadig.humane-test-output/activate!)


; https://github.com/cljfx/cljfx/blob/master/examples/e27_selection_models.clj#L20


; store things through ns refreshes


(defn mount []
  (println "mounting")
  (let [r (fx/create-renderer
            :middleware (fx/wrap-map-desc (fn [state]
                                            {:fx/type spring-lobby/root-view
                                             :state state}))
            :opts {:fx.opt/map-event-handler spring-lobby/event-handler})]
    (fx/mount-renderer spring-lobby/*state r)
    (alter-var-root #'renderer (constantly r))))

(defn unmount []
  (println "looking to unmount")
  (when renderer
    (println "unmounting")
    (fx/unmount-renderer spring-lobby/*state renderer)))


(defn load-and-mount []
  (try
    (println "loading")
    (reset! spring-lobby/*state @state)
    (mount)
    (catch Exception e
      (println e))))

(defn unmount-store-refresh-load-mount []
  (println "storing")
  (reset! state @spring-lobby/*state)
  (unmount)
  (println "refreshing")
  (future
    (try
      (refresh :after 'user/load-and-mount)
      (catch Exception e
        (println e)))))


(defn refresh-on-file-change [context event]
  (when-let [file (:file event)]
    (let [f (io/file file)]
      (when (and (.exists f) (not (.isDirectory f)))
        (unmount-store-refresh-load-mount))))
  context)


(hawk/watch! [{:paths ["src/clj"]
               :handler refresh-on-file-change}])


; doesn't work
;(refresh :after 'user/mount)
(mount)
