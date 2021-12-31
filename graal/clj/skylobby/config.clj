(ns skylobby.config
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :refer [pprint]]
    [skylobby.fs :as fs]
    [taoensso.timbre :as log])
  (:import
    (java.io File)
    (java.net URL)))


(set! *warn-on-reflection* true)


; https://github.com/clojure/clojure/blob/28efe345d5e995dc152a0286fb0be81443a0d9ac/src/clj/clojure/instant.clj#L274-L279
(defn- read-file-tag [cs]
  (io/file cs))
(defn- read-url-tag [spec]
  (URL. spec))


; https://github.com/clojure/clojure/blob/0754746f476c4ddf6a6b699d9547830b2fdad17c/src/clj/clojure/core.clj#L7755-L7761
(def custom-readers
  {'spring-lobby/java.io.File #'skylobby.config/read-file-tag
   'spring-lobby/java.net.URL #'skylobby.config/read-url-tag})


; https://stackoverflow.com/a/23592006/984393
(defmethod print-method File [f ^java.io.Writer w]
  (.write w (str "#spring-lobby/java.io.File " (pr-str (fs/canonical-path f)))))
(defmethod print-method URL [url ^java.io.Writer w]
  (.write w (str "#spring-lobby/java.net.URL " (pr-str (str url)))))


(defn slurp-edn
  "Returns data loaded from a .edn file in this application's root directory."
  [edn-filename]
  (try
    (let [config-file (fs/config-file edn-filename)]
      (log/info "Slurping config edn from" config-file)
      (when (fs/exists? config-file)
        (let [data (->> config-file slurp (edn/read-string {:readers custom-readers}))]
          (if (map? data)
            (do
              (try
                (log/info "Backing up config file that we could parse")
                (fs/copy config-file (fs/config-file (str edn-filename ".known-good")))
                (catch Exception e
                  (log/error e "Error backing up config file")))
              data)
            (do
              (log/warn "Config file data from" edn-filename "is not a map")
              {})))))
    (catch Exception e
      (log/warn e "Exception loading app edn file" edn-filename)
      (try
        (log/info "Copying bad config file for debug")
        (fs/copy (fs/config-file edn-filename) (fs/config-file (str edn-filename ".debug")))
        (catch Exception e
          (log/warn e "Exception copying bad edn file" edn-filename)))
      {})))

(defn spit-edn
  "Writes the given data as edn to the given file in the application directory."
  ([data filename]
   (spit-edn data filename nil))
  ([data filename {:keys [pretty]}]
   (let [output (if pretty
                  (with-out-str (pprint (if (map? data)
                                          (into (sorted-map) data)
                                          data)))
                  (pr-str data))
         parsable (try
                    (edn/read-string {:readers custom-readers} output)
                    true
                    (catch Exception e
                      (log/error e "Config EDN for" filename "does not parse, keeping old file")))
         file (fs/config-file (if parsable
                                filename
                                (str filename ".bad")))]
     (fs/make-parent-dirs file)
     (log/info "Spitting edn to" file)
     (spit file output))))
