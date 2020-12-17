(ns uberjar
  (:require
    [hf.depstar.uberjar])
  (:import
    (javafx.application Platform)))


(def uberjar-opts
  {:jar "alt-spring-lobby.jar" :aot true :main-class "spring-lobby"})


(defn -main [& _args]
  (let [res (hf.depstar.uberjar/run* uberjar-opts)]
    (println res))
  (Platform/exit))
