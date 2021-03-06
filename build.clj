(ns build
  (:require
    [clojure.java.io :as io]
    [clojure.tools.build.api :as b]))


(def lib 'skylobby/skylobby)
(def version (b/git-process {:git-args "describe --tags --abbrev=0"}))
(def src-dirs ["graal/clj" "resources"])
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"
                            :aliases [:graal-deps]}))
(def uber-file (format "target/%s.jar" (name lib)))


(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs src-dirs
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs src-dirs
                  :class-dir class-dir})
  (spit (io/file "resources" (str (name lib) ".version")) version)
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'skylobby.main
           :manifest {"Build-Number" version}}))
