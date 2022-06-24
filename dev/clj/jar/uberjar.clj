(ns uberjar
  (:require
    [clojure.java.io :as io]
    [clojure.tools.build.api :as b]
    [skylobby.git :as git]))


(set! *warn-on-reflection* true)


(def lib 'skylobby/skylobby)
(def here (io/file "."))
(def latest-git-id (git/latest-id here))
(def git-tag (git/tag-or-latest-id here))
(def version
  (if (= latest-git-id git-tag)
    (str "git:" (git/short-git-commit latest-git-id))
    git-tag))
(def src-dirs ["src/clj" "graal/clj" "graal/cljc" "resources"])
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"
                            :aliases [:headless]}))
(def app-name (name lib))
(def uber-file (format "target/%s.jar" app-name))



(defn spit-version-resource []
  (println "Spitting version resource file")
  (spit (io/file "resources" (str app-name ".version")) version))


(defn -main [& _args]
  (println "\nDeleting target\n")
  (b/delete {:path "target"})
  (spit-version-resource)
  (b/copy-dir {:src-dirs src-dirs
               :target-dir class-dir})
  (println "\nCompiling clj\n")
  (b/compile-clj {:basis basis
                  :src-dirs src-dirs
                  :class-dir class-dir
                  :java-opts [
                              "-Dtestfx.robot=glass"
                              "-Dglass.platform=Monocle"
                              "-Dmonocle.platform=Headless"
                              "-Dprism.order=sw"]})
  (println "\nBuilding uberjar\n")
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'spring-lobby.main
           :manifest {"Build-Number" version}})
  (println "\nDeleting classes dir\n")
  (b/delete {:path "target/classes"})
  (println "\nExiting\n")
  (System/exit 0))
