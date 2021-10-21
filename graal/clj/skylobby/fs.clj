(ns skylobby.fs
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [skylobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (java.io File FileOutputStream OutputStream RandomAccessFile)
    (java.nio.file CopyOption Files Path StandardCopyOption)
    (javax.imageio ImageIO)
    (net.sf.sevenzipjbinding ArchiveFormat IArchiveExtractCallback IInArchive ISequentialOutStream
                             PropID SevenZip SevenZipException)
    (net.sf.sevenzipjbinding.impl RandomAccessFileInStream)
    (org.apache.commons.io FilenameUtils FileUtils)
    (org.apache.commons.compress.archivers.sevenz SevenZArchiveEntry SevenZFile SevenZFileOptions)))


(set! *warn-on-reflection* true)


(declare file canonical-path)


(def ^:dynamic app-root-override nil)
(def ^:dynamic replay-sources-override nil)


(def is-7z-initialized (atom false))


(def lock (Object.))


(defn sevenz-lib-filename []
  ; TODO windows, mac
  "lib7-Zip-JBinding.so")

(defn init-7z! []
  (locking lock
    ;(println (SevenZip/getPlatformBestMatch))
    ;(log/info "Initializing 7zip")
    ;(System/load "/home/skynet/git/skynet/skylobby/native/lib7-Zip-JBinding.so")
    ;(System/load "/home/skynet/git/skynet/skylobby/7zip/Linux-i386/lib7-Zip-JBinding.so")
    ;(println (SevenZip/isInitializedSuccessfully))
    ;(System/load "/home/skynet/git/skynet/skylobby/7zip/Linux-amd64")
    ;(System/load "/home/skynet/git/skynet/skylobby/7zip/Linux-amd64/lib7-Zip-JBinding.so")
    ;(System/loadLibrary "7-Zip-JBinding")
    ;(System/loadLibrary "7-Zip-JBinding")
    ;(System/setProperty "sevenzip.no_doprivileged_initialization" "1")
    #_
    (println (System/getProperty "java.library.path"))
    #_
    (if-let [resource (io/resource (sevenz-lib-filename))]
      (let [f (file (sevenz-lib-filename))]
        (FileUtils/copyURLToFile resource f)
        (clojure.lang.RT/load (canonical-path f)))
      (clojure.lang.RT/loadLibrary "7-Zip-JBinding"))
    #_
    (SevenZip/initLoadedLibraries)
    ;(println (SevenZip/isInitializedSuccessfully))
    (SevenZip/initSevenZipFromPlatformJAR)
    ;(println (SevenZip/isInitializedSuccessfully))
    ;(println (SevenZip/getLastInitializationException))
    ;(println (SevenZip/getUsedPlatform))
    (reset! is-7z-initialized true)))

;(init-7z!)


(def app-folder (str "." u/app-name))


(defn canonical-path [^File f]
  (when f
    (try
      (.getCanonicalPath f)
      (catch Exception e
        (log/trace e "Error getting canonical path for" f)))))

(defn ^File file [^File f & args]
  (when f
    (try
      (apply io/file f args)
      (catch Exception e
        (log/warn e "Error creating file from" f "and" args)))))

(defn filename ^String [^File f]
  (when f
    (.getName f)))

(defn exists? [^File f]
  (when f
    (.exists f)))

(defn is-directory? [^File f]
  (when f
    (.isDirectory f)))

(defn is-file? [^File f]
  (when f
    (.isFile f)))

(defn last-modified [^File f]
  (when f
    (.lastModified f)))

(defn parent-file ^File [^File f]
  (when f
    (.getParentFile f)))

; https://stackoverflow.com/a/25267111/984393
(defn descendant?
  "Returns true if f is a possible descendant of dir."
  [dir f]
  (let [fp (canonical-path f)
        dp (canonical-path dir)]
    (and fp dp
      (string/starts-with? fp dp))))

(defn child?
  "Returns true if f is a possible descendant of dir."
  [dir f]
  (and dir f
       (= (canonical-path dir)
          (when-let [parent (parent-file f)]
            (canonical-path parent)))))

(defn list-files [^File f]
  (when f
    (.listFiles f)))

(defn to-path ^Path [^File f]
  (when f
    (.toPath f)))

(defn size [^File f]
  (when f
    (when-let [path (.toPath f)]
      (Files/size path))))

(defn make-dirs [^File f]
  (when f
    (.mkdirs f)))

(defn make-parent-dirs [f]
  (when-not (exists? f)
    (make-dirs (parent-file f))))

(defn set-executable
  ([f]
   (set-executable f true))
  ([^File f executable]
   (when (is-file? f)
     (.setExecutable f executable))))

(defn os-name []
  (System/getProperty "os.name"))

(defn os-version []
  (System/getProperty "os.version"))

(defn user-home []
  (System/getProperty "user.home"))

(defn user-name []
  (System/getProperty "user.name"))

(defn get-sys-data []
  {:os-name (os-name)
   :os-version (os-version)
   :user-home (user-home)
   :user-name (user-name)})

(defn windows?
  ([]
   (windows? (get-sys-data)))
  ([{:keys [os-name]}]
   (string/includes? os-name "Windows")))

(defn linux?
  ([]
   (linux? (get-sys-data)))
  ([{:keys [os-name]}]
   (string/includes? os-name "Linux")))

(defn wsl?
  "Returns true if this system appears to be the Windows Subsystem for Linux."
  ([]
   (wsl? (get-sys-data)))
  ([sys-data]
   (let [{:keys [os-name os-version]} sys-data]
     (and
       os-name
       (string/includes? os-name "Linux")
       os-version
       (or
         (string/includes? os-version "Microsoft") ; WSL
         (string/includes? os-version "microsoft"))))))

(defn wsl-or-windows? []
  (or (windows?) (wsl?)))

(defn platform
  ([]
   (platform (get-sys-data)))
  ([{:keys [os-name]}]
   (if (and os-name
            (string/includes? os-name "Linux")
            (not (wsl?)))
     "linux64"
     "win32")))

(defn platform64
  ([]
   (platform64 (get-sys-data)))
  ([{:keys [os-name]}]
   (if (and os-name
            (string/includes? os-name "Linux")
            (not (wsl?)))
     "linux64"
     "win64")))

(defn wslpath
  "Returns the host path if in WSL, otherwise returns the original path."
  [^File f]
  (let [path (canonical-path f)]
    (if (wsl?)
      (let [command ["wslpath" "-w" path]
            ^"[Ljava.lang.String;" cmdarray (into-array String command)
            runtime (Runtime/getRuntime)
            process (.exec runtime cmdarray)]
        (.waitFor process 1000 java.util.concurrent.TimeUnit/MILLISECONDS)
        (let [windows-path (string/trim (slurp (.getInputStream process)))]
          (log/info "Converted path" path "to" windows-path)
          windows-path))
      path)))

(defn executable [common-name]
  (str
    common-name
    (when (wsl-or-windows?)
      ".exe")))

(defn spring-executable []
  (executable "spring"))

(defn spring-headless-executable []
  (executable "spring-headless"))

(defn pr-downloader-executable []
  (executable "pr-downloader"))

(defn pr-downloader-file [engine-dir]
  (some
    #(when (and (is-file? %)
                (exists? %))
       %)
    [(io/file engine-dir (pr-downloader-executable))
     (io/file engine-dir "bin" (pr-downloader-executable))]))


(defn app-root
  "Returns the root directory for this application"
  []
  (or (when app-root-override
        (file app-root-override))
      (let [{:keys [os-name user-name user-home] :as sys-data} (get-sys-data)]
        (cond
          (string/includes? os-name "Linux")
          (if (wsl? sys-data)
            (io/file "/mnt" "c" "Users" user-name app-folder)
            (io/file user-home app-folder))
          (string/includes? os-name "Windows")
          (io/file user-home app-folder)
          :else
          (io/file user-home app-folder)))))

(defn config-root
  []
  (if (wsl?)
    (io/file (app-root) "wsl")
    (app-root)))

(defn config-file
  [& path]
  (apply io/file (config-root) path))

(defn spring-settings-root
  []
  (file (app-root) "spring-settings"))

(defn download-dir ^File
  []
  (io/file (app-root) "download"))

(defn default-spring-root
  "Returns the default isolation dir for spring in this app."
  ^File
  []
  (io/file (app-root) "spring"))


; TODO same as springlobby root??
(defn spring-root
  "Returns the root directory for Spring"
  ^File []
  (let [{:keys [os-name user-name user-home] :as sys-data} (get-sys-data)]
    (cond
      (string/includes? os-name "Linux")
      (if (wsl? sys-data)
        (io/file "/mnt" "c" "Users" user-name "Documents" "My Games" "Spring")
        (let [snap-dir (io/file user-home "snap" "springlobby-nsg" "common" ".spring")]
          (if (.exists snap-dir)
            snap-dir
            (io/file user-home ".spring"))))
      (string/includes? os-name "Windows")
      (io/file user-home "Documents" "My Games" "Spring")
      :else
      (io/file user-home ".spring"))))

(defn bar-root
  "Returns the root directory for BAR"
  ^File []
  (let [{:keys [os-name user-name user-home] :as sys-data} (get-sys-data)]
    (cond
      (string/includes? os-name "Linux")
      (if (wsl? sys-data)
        (io/file "/mnt" "c" "Users" user-name "AppData" "Local" "Programs" "Beyond-All-Reason" "data")
        (io/file user-home "Documents" "Beyond All Reason")) ; appimage, but others?
      (string/includes? os-name "Windows")
      (io/file user-home "AppData" "Local" "Programs" "Beyond-All-Reason" "data")
      :else
      (io/file user-home ".spring"))))

(defn zerok-root
  "Returns the root directory for Zero-K"
  ^File []
  (let [{:keys [os-name user-home] :as sys-data} (get-sys-data)]
    (cond
      (string/includes? os-name "Linux")
      (if (wsl? sys-data)
        (io/file "/mnt" "c" "Program Files (x86)" "Steam" "steamapps" "common" "Zero-K") ; not always
        (io/file user-home ".local" "share" "Steam" "steamapps" "common" "Zero-K")) ; ?
      (string/includes? os-name "Windows")
      (io/file "C:\\" "Program Files (x86)" "Steam" "steamapps" "common" "Zero-K") ; not always
      :else
      (io/file user-home ".local" "share" "Steam" "steamapps" "common" "Zero-K"))))

(defn springlobby-root
  "Returns the root directory for Spring"
  []
  (let [{:keys [os-name user-name user-home] :as sys-data} (get-sys-data)]
    (cond
      (string/includes? os-name "Linux")
      (if (wsl? sys-data)
        (io/file "/mnt" "c" "Users" user-name "AppData" "Roaming" "springlobby")
        (let [linux-home (io/file user-home ".springlobby")]
          (if (.exists linux-home)
            linux-home
            (io/file user-home "snap" "springlobby-nsg" "common" ".springlobby"))))
      (string/includes? os-name "Windows")
      (io/file user-home "AppData" "Roaming" "springlobby")
      :else
      (io/file user-home ".springlobby"))))


(defn replays-dir
  [root]
  (io/file root "demos"))

(defn builtin-replay-sources []
  (if replay-sources-override
    replay-sources-override
    [{:replay-source-name "skylobby"
      :file (replays-dir (default-spring-root))
      :builtin true}
     {:replay-source-name "Beyond All Reason"
      :file (replays-dir (bar-root))
      :builtin true}
     {:replay-source-name "Spring"
      :file (replays-dir (spring-root))
      :builtin true}]))


(defn replay-files
  ([dir]
   (replay-files dir nil))
  ([dir {:keys [recursive]}]
   (let [files
         (->> dir
              list-files
              (filter is-file?)
              (filter (comp #(string/ends-with? % ".sdfz") filename)))]
     (if recursive
       (let [subdirs (->> dir
                          list-files
                          (filter is-directory?))]
         (concat
           files
           (mapcat #(replay-files % {:recursive true})
                   subdirs)))
       files))))


(defn spring-archive? [filename]
  (and filename
    (or (string/ends-with? filename ".sd7")
        (string/ends-with? filename ".sdz"))))

(defn spring-files-and-dirs [f]
  (->> f
       list-files
       (filter
         (some-fn
           (every-pred is-file? (comp spring-archive? filename))
           (every-pred
             is-directory?
             #(string/ends-with? (filename %) ".sdd"))))))

(defn map-files
  [root]
  (spring-files-and-dirs (io/file root "maps")))

(defn without-extension [^String fname]
  (when fname
    (if (string/includes? fname ".")
      (subs fname 0 (.lastIndexOf fname "."))
      fname)))

(defn- close-7z-stream [callback-state]
  (when-let [^OutputStream output-stream (:stream @callback-state)]
    (try
      (.close output-stream)
      (catch Exception e
        (log/error e "Error closing output stream")
        (throw (SevenZipException. "Error closing output stream"))))))

(deftype OutToOutStream [^java.io.OutputStream output-stream]
  ISequentialOutStream
  (write [_this data]
    (.write output-stream data 0 (count data))
    (count data)))

(deftype ExtractToDiskCallback [^IInArchive archive callback-state dest]
  IArchiveExtractCallback
  (getStream [_this index _extract-ask-mode]
    (swap! callback-state assoc :index index)
    (close-7z-stream callback-state)
    (try
      (let [path (str (.getProperty archive index PropID/PATH))
            to (io/file dest path)
            is-folder (.getProperty archive index PropID/IS_FOLDER)]
        (if is-folder
          (do
            (log/debug "Creating dir" to)
            (FileUtils/forceMkdir to))
          (do
            (FileUtils/forceMkdir (.getParentFile to))
            (log/debug "Stream for index" index "to" to)
            (let [fos (FileOutputStream. to)] ; not with-open
              (swap! callback-state assoc :stream fos)
              (->OutToOutStream fos)))))
      (catch Throwable e
        (log/error e "Error getting stream for item" index))))
  (prepareOperation [_this extract-ask-mode]
    (swap! callback-state assoc :extract-ask-mode extract-ask-mode)
    (log/trace "preparing" extract-ask-mode))
  (setOperationResult [_this extract-operation-result]
    (close-7z-stream callback-state)
    (swap! callback-state assoc :operation-result extract-operation-result)
    (log/trace "result" extract-operation-result))
  (setTotal [_this total]
    (swap! callback-state assoc :total total)
    (log/trace "total" total))
  (setCompleted [_this complete]
    (swap! callback-state assoc :complete complete)
    (log/trace "completed" complete)))

(defn extract-7z-fast
  ([^File f]
   (extract-7z-fast f (io/file (parent-file f) (without-extension (filename f)))))
  ([^File f ^File dest]
   (when-not @is-7z-initialized
     (init-7z!))
   (let [before (u/curr-millis)]
     (log/info "Extracting" f "to" dest)
     (FileUtils/forceMkdir dest)
     (with-open [raf (new RandomAccessFile f "r")
                 rafis (new RandomAccessFileInStream raf)
                 archive (SevenZip/openInArchive ArchiveFormat/SEVEN_ZIP rafis)]
       (log/debug f "is of format" (.getArchiveFormat archive)
                  "and has" (.getNumberOfItems archive) "items")
       ; http://sevenzipjbind.sourceforge.net/javadoc/net/sf/sevenzipjbinding/IInArchive.html#extract(int[],%20boolean,%20net.sf.sevenzipjbinding.IArchiveExtractCallback)
       (let [callback-state (atom {})]
         (.extract archive
                   nil ; all items
                   false ; not test mode
                   ; http://sevenzipjbind.sourceforge.net/javadoc/net/sf/sevenzipjbinding/IArchiveExtractCallback.html
                   ; https://gist.github.com/borisbrodski/6120309
                   (->ExtractToDiskCallback archive callback-state dest))))
     (log/info "Finished extracting" f "to" dest "in" (- (u/curr-millis) before) "ms"))))


(defn extract-7z-apache
  ([^File f]
   (extract-7z-apache f (io/file (parent-file f) (without-extension (filename f)))))
  ([^File f ^File dest]
   (log/info "Extracting" f "to" dest)
   (FileUtils/forceMkdir dest)
   (let [options-builder (doto (SevenZFileOptions/builder)
                           (.withTryToRecoverBrokenArchives true)
                           (.withUseDefaultNameForUnnamedEntries true))
         ^SevenZFileOptions options (.build options-builder)
         sevenz-file (new SevenZFile f options)]
     (loop []
       (when-let [^SevenZArchiveEntry entry (try
                                              (.getNextEntry sevenz-file)
                                              (catch Exception e
                                                (log/error e "Error getting next entry")
                                                :skip))]
         (if (not= :skip entry)
           (let [
                 size (.getSize entry)
                 content (byte-array size)
                 entry-name (.getName entry)
                 to (io/file dest entry-name)]
             (println "Reading" entry-name "size" size)
             (.read sevenz-file content 0 size)
             (if (.isDirectory entry)
               (FileUtils/forceMkdir to)
               (do
                 (FileUtils/forceMkdir (.getParentFile to))
                 (with-open [fos (FileOutputStream. to)]
                   (.write fos content 0 size)))))
           (println "Skip"))
         (recur))))))


(defn sync-version-to-engine-version
  "Returns the Spring engine version from a sync version. For some reason, engine '103.0' has sync
  version '103' whereas for engine 104.0.1-1553-gd3c0012 maintenance the sync version is the same,
  '104.0.1-1553-gd3c0012 maintenance'."
  [sync-version]
  (cond
    (string/blank? sync-version)
    ""
    (= sync-version (string/replace sync-version #"[^\d]" ""))
    (str sync-version ".0")
    :else
    sync-version))

(defn sync-version [engine-dir]
  (let [engine-exe (io/file engine-dir (spring-headless-executable))
        _ (set-executable engine-exe)
        command [(canonical-path engine-exe) "--sync-version"]
        ^"[Ljava.lang.String;" cmdarray (into-array String command)
        runtime (Runtime/getRuntime)
        process (.exec runtime cmdarray)]
    (.waitFor process 1000 java.util.concurrent.TimeUnit/MILLISECONDS)
    (let [sync-version (string/trim (slurp (.getInputStream process)))]
      (log/info "Discovered sync-version of" engine-exe "is" (str "'" sync-version "'"))
      sync-version)))

(defn engines-dir
  [root]
  (io/file root "engine"))

(defn spring-engine-dir? [dir]
  (and (is-directory? dir)
       (is-file? (io/file dir (spring-executable)))
       (is-file? (io/file dir (spring-headless-executable)))))

(defn engine-dirs
  [root]
  (let [engines-folder (->> (list-files (io/file root "engine"))
                            seq
                            (filter is-directory?))]
    (concat
      (mapcat
        (fn [dir]
          (->> (list-files dir)
               seq
               (filter spring-engine-dir?)))
        engines-folder)
      (filter spring-engine-dir? engines-folder))))

(defn engine-bots
  ([engine-file]
   (or
     (try
       (when engine-file
         (let [ai-skirmish-dir (io/file engine-file "AI" "Skirmish")
               ai-dirs (some->> (list-files ai-skirmish-dir)
                                seq
                                (filter is-directory?))]
           (mapcat
             (fn [^java.io.File ai-dir]
               (->> (list-files ai-dir)
                    (filter is-directory?)
                    (map
                      (fn [^java.io.File version-dir]
                        {:bot-name (filename ai-dir)
                         :bot-version (filename version-dir)}))))
             ai-dirs)))
       (catch Exception e
         (log/error e "Error loading bots")))
     [])))

(defn engine-data [^File engine-dir]
  (let [sync-version (sync-version engine-dir)]
    {:file engine-dir
     :sync-version sync-version
     :engine-version (sync-version-to-engine-version sync-version)
     :engine-bots (engine-bots engine-dir)}))


(defn mods-dir
  [root]
  (io/file root "games"))

(defn mod-file
  [root mod-filename]
  (when mod-filename
    (io/file (mods-dir root) mod-filename)))

(defn map-names []
  (->> (list-files (io/file (spring-root) "maps"))
       seq
       (filter is-file?)
       (map
         (fn [^File file]
           (let [filename (filename file)]
             (first (string/split filename #"\.")))))))

(defn map-filename [map-name]
  (when map-name
    (str
      (string/lower-case (string/replace map-name #"\s" "_"))
      ".sd7")))

(defn maps-dir
  [root]
  (io/file root "maps"))

(defn map-file
  [root map-filename]
  (when map-filename
    (io/file (maps-dir root) map-filename)))

(defn spring-config-line [lines field]
  (nth
    (some->> lines
             (filter #(string/includes? % field))
             first
             (re-find #"\s*(.*)=(.*)\s*;"))
    2))

(defn before-dot
  [path]
  (-> path
      (string/split #"/")
      last
      (string/split #"\.")
      first))

(defn map-name [path]
  (FilenameUtils/getBaseName path))

(def ^java.io.File maps-cache-root
  (io/file (app-root) "maps-cache"))

(defn minimap-image-cache-file
  ([map-name]
   (minimap-image-cache-file map-name nil))
  ([map-name {:keys [root minimap-type] :or {root maps-cache-root minimap-type "minimap"}}]
   (io/file root (str map-name "." minimap-type ".png"))))

(defn copy-dir
  [^java.io.File source ^java.io.File dest]
  (if (exists? source)
    (do
      (FileUtils/forceMkdir dest)
      (log/info "Copying" source "to" dest)
      (FileUtils/copyDirectory source dest))
    (log/warn "No source to copy from" source "to" dest)))

(defn- java-nio-copy
  ([^File source ^File dest]
   (java-nio-copy source dest nil))
  ([^File source ^File dest {:keys [copy-options]
                             :or {copy-options
                                  [StandardCopyOption/COPY_ATTRIBUTES
                                   StandardCopyOption/REPLACE_EXISTING]}}]
   (let [^Path source-path (to-path source)
         ^Path dest-path (to-path dest)
         ^"[Ljava.nio.file.CopyOption;" options (into-array ^CopyOption copy-options)]
     (make-parent-dirs dest)
     (Files/copy source-path dest-path options))))

(defn copy
  "Copy a file or directory from source to dest."
  [source dest]
  (if (is-directory? source)
    (copy-dir source dest)
    (java-nio-copy source dest)))

(defn copy-missing [source-dir dest-dir]
  (let [source-path (to-path source-dir)]
    (doseq [source (file-seq source-dir)]
      (let [dest (io/file dest-dir (str (.relativize source-path (to-path source))))]
        (cond
          (not (is-file? source)) (log/warn "Not a file" source)
          (exists? dest) (log/trace "Skipping copy of" source "since it exists at" dest)
          :else
          (try
            (log/info "Copying" source "to" dest)
            (java-nio-copy source dest {:copy-options [StandardCopyOption/COPY_ATTRIBUTES]})
            (catch Exception e
              (log/warn e "Unable to copy file" source "to" dest
                        {:exists (exists? dest)}))))))))

(defn write-image-png [^java.awt.image.RenderedImage image ^File dest]
  (log/debug "Writing image to" dest)
  (ImageIO/write image "png" dest))

(defn file-status [file-cache f]
  (when f
    (let [path (if (string? f)
                 f
                 (canonical-path f))]
      (get file-cache path))))

(defn file-exists? [file-cache f]
  (boolean (:exists (file-status file-cache f))))

(defn file-cache-data [f]
  (if f
    {:canonical-path (canonical-path f)
     :exists (exists? f)
     :is-directory (is-directory? f)
     :last-modified (last-modified f)}
    (log/warn (ex-info "stacktrace" {}) "Attempt to update file cache for nil file")))

(defn file-cache-by-path [statuses]
  (->> statuses
       (filter some?)
       (map (juxt :canonical-path identity))
       (into {})))

(defn update-file-cache!
  "Updates the file cache in state for this file. This is so that we don't need to do IO in render,
  and any updates to file statuses here can now cause UI redraws, which is good."
  [state-atom & fs]
  (let [statuses (for [f fs]
                   (let [f (if (string? f)
                             (io/file f)
                             f)]
                     (file-cache-data f)))
        status-by-path (file-cache-by-path statuses)]
    (swap! state-atom update :file-cache merge status-by-path)
    status-by-path))
