(ns uberjar
  (:require
    [clojure.java.io :as io]
    [clojure.tools.cli.api :as deps]
    [clojure.data.xml :as xml]
    [hf.depstar.uberjar]
    [me.raynes.fs :as fs]
    [spring-lobby.git :as git])
  (:import
    (javafx.application Platform)))


(def uberjar-opts
  {:jar "dist/alt-spring-lobby.jar"
   :aot true
   :main-class "spring-lobby"})


(defn pom []
  (println "Deleting old pom")
  (fs/delete "pom.xml")
  (println "Generating pom")
  (deps/mvn-pom nil))

(xml/alias-uri 'pom "http://maven.apache.org/POM/4.0.0")

(defn fix-pom-version []
  (println "Fixing pom version")
  (let [version (spring-lobby.git/tag-or-latest-id (clojure.java.io/file "."))
        pom (clojure.data.xml/parse-str (slurp "pom.xml"))
        modified (update pom :content
                         (fn [contents]
                           (map
                             (fn [content]
                               (if (= ::pom/version (:tag content))
                                 (assoc content :content [version])
                                 content))
                             contents)))]
    (spit "pom.xml" (clojure.data.xml/emit-str modified))
    modified))

(defn uberjar []
  (hf.depstar.uberjar/run* uberjar-opts))

(defn -main [& _args]
  (pom)
  (fix-pom-version)
  (uberjar)
  (Platform/exit))
