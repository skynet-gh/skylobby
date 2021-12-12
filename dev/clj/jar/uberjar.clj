(ns uberjar
  (:require
    [clojure.java.io :as io]
    [clojure.tools.build.api :as b]
    [spring-lobby.git :as git])
  (:import
    (javafx.application Platform)))


(def lib 'skylobby/skylobby)
(def version (spring-lobby.git/tag-or-latest-id (io/file ".")))
(def src-dirs ["src/clj" "graal/clj" "resources"])
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def app-name (name lib))
(def uber-file (format "target/%s.jar" app-name version))



(defn spit-version-resource []
  (println "Spitting version resource file")
  (spit (io/file "resources" (str app-name ".version")) version))


(defn -main [& args]
  (println "\nDeleting target\n")
  (b/delete {:path "target"})
  (spit-version-resource)
  (b/copy-dir {:src-dirs src-dirs
               :target-dir class-dir})
  (println "\nCompiling clj\n")
  (b/compile-clj {:basis basis
                  :src-dirs src-dirs
                  :class-dir class-dir})
  (println "\nBuilding uberjar\n")
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'spring-lobby.main
           :manifest {"Build-Number" version}})
  (println "\nDeleting classes dir\n")
  (b/delete {:path "target/classes"})
  (println "\nExiting\n")
  (Platform/exit)
  (System/exit 0))
