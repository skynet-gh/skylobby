(ns user
  (:require
    [clojure.tools.namespace.repl :refer [refresh]]
    [repl]
    [spring-lobby]))



(defn init []
  (alter-var-root #'repl/*renderer* spring-lobby/mount-renderer))

(defn render []
  (if repl/*renderer*
    (repl/*renderer*)
    (init)))
