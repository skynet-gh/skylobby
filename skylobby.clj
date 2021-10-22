(ns skylobby
  (:require
    [clojure.tools.build.api :as b]))


(def lib 'skylobby/skylobby)
(def version "0.6.18") ; TODO
(def src-dirs ["src/clj" "graal/clj" "resources"])
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))


(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs src-dirs
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs src-dirs
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'spring_lobby.main
           :manifest {"Build-Number" version}}))
