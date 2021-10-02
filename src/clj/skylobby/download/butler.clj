(ns skylobby.download.butler
  (:require
    [cheshire.core :as json]
    [clj-http.client :as clj-http]
    [clojure.java.io :as io]
    [spring-lobby.fs :as fs]
    [spring-lobby.http :as http]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)


; https://github.com/gajop/spring-launcher/blob/master/src/nextgen_downloader.js


(def pkg-url "https://content.spring-launcher.com/pkg")
(def fallback-url "https://spring-launcher.ams3.digitaloceanspaces.com/pkg")

(def api-backend "https://backend.spring-launcher.com/api")


(defn pkg-dir [root]
  (fs/file root "pkgs"))


(defn version-from-spring-name [spring-name]
  (->> (clj-http/post
         (str api-backend "/versions/from-springname/")
         {:body (json/generate-string {:springName spring-name})
          :content-type :json
          :accept :json
          :as :json})
       :body))

; {:nextgenName "beyond-all-reason/Beyond-All-Reason@main:27"}


(defn butler-download-url
  ([]
   (let [platform (if (fs/windows?) "windows-amd64"
                    "linux-amd64")]
     (butler-download-url platform)))
  ([platform]
   (str "https://broth.itch.ovh/butler/" platform "/LATEST/archive/default")))

(defn get-butler [state-atom]
  (let [dest-file (fs/file (fs/download-dir) "butler.zip")]
    @(http/download-file state-atom (butler-download-url) dest-file)
    (fs/extract-7z-fast dest-file (fs/file (fs/download-dir) "butler"))))


(defn download-with-butler [url]
  (future
    (try
      (let [butler-file (fs/file (fs/download-dir) "butler" (fs/executable "butler"))
            _ (fs/set-executable butler-file)
            tmp-dest (fs/canonical-path (fs/file (fs/download-dir) "butler-tmp"))
            command [(fs/canonical-path butler-file)
                     "-j" "-v" "dl" url tmp-dest]
            runtime (Runtime/getRuntime)]
        (log/info "Running '" command "'")
        (let [^"[Ljava.lang.String;" cmdarray (into-array String command)
              ^"[Ljava.lang.String;" envp nil
              ^java.lang.Process process (.exec runtime cmdarray envp ^java.io.File (fs/app-root))]
          (future
            (with-open [^java.io.BufferedReader reader (io/reader (.getInputStream process))]
              (loop []
                (if-let [line (.readLine reader)]
                  (do
                    (log/info "(butler" url "out)" line)
                    (recur))
                  (log/info "butler" url "stdout stream closed")))))
          (future
            (with-open [^java.io.BufferedReader reader (io/reader (.getErrorStream process))]
              (loop []
                (if-let [line (.readLine reader)]
                  (do
                    (log/info "(butler" url "err)" line)
                    (recur))
                  (log/info "butler" url "stderr stream closed")))))
          (.waitFor process)))
      (catch Exception e
        (log/error e "Error downloading" url)))))
