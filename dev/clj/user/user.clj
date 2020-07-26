(ns user
  (:require
    [cljfx.api :as fx]
    [clojure.core.cache :as cache]
    [clojure.tools.namespace.repl :refer [refresh set-refresh-dirs]]
    [repl]
    [spring-lobby]))


(set-refresh-dirs "dev/clj/user" "dev/clj/test" "src/clj" "test/clj")


; https://github.com/cljfx/cljfx/blob/master/examples/e27_selection_models.clj#L20


(defn unmount []
  (when repl/renderer
    (fx/unmount-renderer spring-lobby/*state repl/renderer)))

(defn mount []
  (reset! spring-lobby/*state @repl/state)
  (unmount)
  (let [r (fx/create-renderer
            :middleware (fx/wrap-map-desc (fn [state]
                                            {:fx/type spring-lobby/root-view
                                             :state state}))
            :opts {:fx.opt/map-event-handler spring-lobby/event-handler})]
    (fx/mount-renderer spring-lobby/*state r)
    (alter-var-root #'repl/renderer (constantly r))))

(defn restart []
  (reset! repl/state @spring-lobby/*state)
  (refresh :after 'user/mount))


(defn reset []
  (unmount)
  (reset! repl/state {})
  (reset! spring-lobby/*state {})
  (mount))
