(ns user
  (:require
    [cljfx.api :as fx]
    [clojure.core.cache :as cache]
    [clojure.tools.namespace.repl :refer [refresh set-refresh-dirs]]
    [repl]
    [spring-lobby]))


(set-refresh-dirs "dev/clj/user" "dev/clj/test" "src/clj" "test/clj")


(defn re []
  (when repl/renderer
    (fx/unmount-renderer spring-lobby/*state repl/renderer))
  (let [r (fx/create-renderer
            :middleware (fx/wrap-map-desc (fn [state]
                                            {:fx/type spring-lobby/root-view
                                             :state state}))
            :opts {:fx.opt/map-event-handler spring-lobby/event-handler})]
    (reset! spring-lobby/*state @repl/state)
    (fx/mount-renderer spring-lobby/*state r)
    (alter-var-root #'repl/renderer (constantly r))))

(re)

(defn restart []
  (reset! repl/state @spring-lobby/*state)
  (refresh :after 'user/re))
