(ns uberjar
  (:require
    [clojure.java.io :as io]
    [clojure.tools.cli.api :as deps]
    [clojure.data.xml :as xml]
    [hf.depstar.uberjar]
    [me.raynes.fs :as raynes-fs]
    [spring-lobby.git :as git])
  (:import
    (javafx.application Platform)))


(def uberjar-opts
  {:jar "dist/alt-spring-lobby.jar"
   :aot true
   :main-class "spring-lobby"})


(defn pom []
  (println "Deleting old pom")
  (raynes-fs/delete "pom.xml")
  (println "Generating pom")
  (deps/mvn-pom nil))

(xml/alias-uri 'pom "http://maven.apache.org/POM/4.0.0")

(defn version []
  (spring-lobby.git/tag-or-latest-id (clojure.java.io/file ".")))

(defn fix-pom-version []
  (println "Fixing pom version")
  (let [version (version)
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

(defn spit-version-resource []
  (println "Spitting version resource file")
  (spit (io/file "resources" "alt-spring-lobby.version") (version)))

(defn uberjar []
  (hf.depstar.uberjar/run* uberjar-opts))

(defn fix-manifest []
  (println "Fixing version in jar manifest")
  (let [dist-dir "dist"
        jar-path (.getCanonicalPath
                   (clojure.java.io/file dist-dir "alt-spring-lobby.jar"))
        mf-file (clojure.java.io/file dist-dir "manifest-add.txt")]
    (spit mf-file (str "Build-Number: " (version) "\n"))
    (let [command ["jar" "ufm" jar-path (.getCanonicalPath mf-file)]
          ^"[Ljava.lang.String;" cmdarray (into-array String command)
          runtime (Runtime/getRuntime)
          process (.exec runtime cmdarray)
          res (.waitFor process 10000 java.util.concurrent.TimeUnit/MILLISECONDS)
          out (slurp (.getInputStream process))
          err (slurp (.getErrorStream process))]
      {:res res
       :out out
       :err err})))

(defn -main [& _args]
  (pom)
  (fix-pom-version)
  (spit-version-resource)
  (uberjar)
  (fix-manifest)
  (Platform/exit))
