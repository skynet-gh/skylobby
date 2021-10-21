(ns skylobby-cli
  (:require
    [clojure.tools.build.api :as b]))


(def lib 'skylobby/skylobby-cli)
(def version "0.0.1")
(def src-dir "graal/clj")
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"
                            :aliases [:skylobby-cli-native]}))
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))


(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs [src-dir]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs [src-dir]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'skylobby.cli}))
