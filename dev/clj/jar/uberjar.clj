(ns uberjar
  (:require
    clojure.core.async
    [clojure.java.io :as io]
    [clojure.tools.cli :as cli]
    [clojure.tools.cli.api :as deps]
    [clojure.data.xml :as xml]
    [hf.depstar.uberjar]
    [me.raynes.fs :as raynes-fs]
    [spring-lobby.git :as git]
    [spring-lobby.util :as u])
  (:import
    (javafx.application Platform)))


(def default-uberjar-opts
  {:aot true})


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
  (spit (io/file "resources" (str u/app-name ".version")) (version)))

(defn uberjar [options]
  (hf.depstar.uberjar/run* options))

(defn fix-manifest []
  (println "Fixing version in jar manifest")
  (let [dist-dir "dist"
        jar-path (.getCanonicalPath
                   (clojure.java.io/file dist-dir (str u/app-name ".jar")))
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


(def cli-options
  [[nil "--main-class MAIN" "Main class"
    :default "spring-lobby.main"]
   [nil "--jar JAR" "Jar filename"
    :default (str "dist/" u/app-name ".jar")]])

(defn -main [& args]
  (let [{:keys [options]} (cli/parse-opts args cli-options)]
    (pom)
    (fix-pom-version)
    (spit-version-resource)
    (uberjar (merge default-uberjar-opts options))
    (fix-manifest)
    (println "\nSuccessfully built jar, exiting\n")
    (Platform/exit)))
