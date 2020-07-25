(ns user
  (:require
    [cljfx.api :as fx]
    [clojure.core.cache :as cache]
    [clojure.tools.namespace.repl :refer [refresh set-refresh-dirs]]
    [spring-lobby]))


(set-refresh-dirs "src/clj" "test/clj")


(def ^:dynamic renderer nil)


(defn re []
  (when renderer
    (fx/unmount-renderer spring-lobby/*state renderer))
  (let [r (fx/create-renderer
            :middleware (fx/wrap-map-desc (fn [state]
                                            {:fx/type spring-lobby/root-view
                                             :state state}))
            :opts {:fx.opt/map-event-handler spring-lobby/event-handler})]
    (fx/mount-renderer spring-lobby/*state r)
    (alter-var-root #'renderer (constantly r))))

(re)
