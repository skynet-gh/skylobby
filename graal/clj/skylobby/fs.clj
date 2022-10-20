(ns skylobby.fs
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as string]
    [me.raynes.fs :as raynes-fs]
    [skylobby.fs.smf :as smf]
    [skylobby.git :as git]
    [skylobby.lua :as lua]
    [skylobby.spring.script :as spring-script]
    [skylobby.util :as u]
    [taoensso.nippy :as nippy]
    [taoensso.timbre :as log])
  (:import
    (java.io ByteArrayOutputStream File FileOutputStream OutputStream RandomAccessFile)
    (java.nio.file CopyOption Files Path StandardCopyOption)
    (java.nio.file.attribute FileAttribute)
    (java.util.zip ZipEntry ZipFile)
    (javax.imageio ImageIO)
    (net.sf.sevenzipjbinding ArchiveFormat IArchiveExtractCallback IInArchive ISequentialOutStream
                             PropID SevenZip SevenZipException)
    (net.sf.sevenzipjbinding.impl RandomAccessFileInStream)
    (net.sf.sevenzipjbinding.simple ISimpleInArchiveItem)
    (org.apache.commons.io FilenameUtils FileUtils)
    (org.apache.commons.compress.archivers.sevenz SevenZArchiveEntry SevenZFile SevenZFileOptions)))


(set! *warn-on-reflection* true)


(declare file canonical-path)


(def ^:dynamic app-root-override nil)
(def ^:dynamic replay-sources-override nil)

(def ^:dynamic is-wsl-override nil)


(def is-7z-initialized (atom false))


(def lock (Object.))


(defn init-7z! []
  (locking lock
    (let [^"[Ljava.nio.file.attribute.FileAttribute;" attributes (into-array FileAttribute [])
          temp-dir (.toFile (Files/createTempDirectory "skylobby-7z" attributes))]
      (SevenZip/initSevenZipFromPlatformJAR temp-dir))
    (reset! is-7z-initialized true)))


(def app-folder (str "." u/app-name))


(defn canonical-path [^File f]
  (when f
    (try
      (.getCanonicalPath f)
      (catch Exception e
        (log/trace e "Error getting canonical path for" f)))))

(defn file
  ^File
  [^File f & args]
  (when f
    (try
      (apply io/file f args)
      (catch Exception e
        (log/warn e "Error creating file from" f "and" args)))))

(defn join
  ^String
  [& args]
  (string/join File/separator args))

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

(defn first-existing-parent [f]
  (when f
    (when-let [parent (parent-file f)]
      (if (exists? parent)
        parent
        (first-existing-parent parent)))))

(defn list-files [^File f]
  (when f
    (log/info "Listing files in" f)
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

(defn mac?
  ([]
   (mac? (get-sys-data)))
  ([{:keys [os-name]}]
   (string/includes? os-name "Mac OS X")))

(defn wsl?
  "Returns true if this system appears to be the Windows Subsystem for Linux."
  ([]
   (wsl? (get-sys-data)))
  ([sys-data]
   (if (some? is-wsl-override)
     is-wsl-override
     (let [{:keys [os-name os-version]} sys-data]
       (and
         os-name
         (string/includes? os-name "Linux")
         os-version
         (or
           (string/includes? os-version "Microsoft") ; WSL
           (string/includes? os-version "microsoft")))))))

(defn wsl-or-windows? []
  (or (windows?) (wsl?)))

(defn platform
  ([]
   (platform (get-sys-data)))
  ([{:keys [os-name] :as sys-data}]
   (when os-name
     (cond
       (mac? sys-data)
       "mac64"
       (and (linux? sys-data)
            (not (wsl?)))
       "linux64"
       :else
       "win64"))))

(defn platform64
  ([]
   (platform64 (get-sys-data)))
  ([{:keys [os-name] :as sys-data}]
   (when os-name
     (cond
       (mac? sys-data)
       "mac64"
       (and (linux? sys-data)
            (not (wsl?)))
       "linux64"
       :else
       "win64"))))

(defn platforms
  ([]
   (platforms (get-sys-data)))
  ([{:keys [os-name] :as sys-data}]
   (when os-name
     (cond
       (mac? sys-data)
       [
        "mac64"
        "mac32"]
       (and (linux? sys-data)
            (not (wsl?)))
       [
        "linux64"
        "linux32"]
       :else
       [
        "win64"
        "win32"]))))

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

(defn spring-dedicated-executable []
  (executable "spring-dedicated"))

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

(defn download-file ^File
  [filename]
  (file (download-dir) filename))

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
              (filter (comp (some-fn
                              #(string/ends-with? % ".sdfz")
                              #(string/ends-with? % ".sdf"))
                            filename)))]
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

(defn is-sdd-filename? [filename]
  (when filename
    (string/ends-with? (string/lower-case filename) ".sdd")))

(defn spring-files-and-dirs [f]
  (->> f
       list-files
       (filter
         (some-fn
           (every-pred is-file? (comp spring-archive? filename))
           (every-pred
             is-directory?
             (comp is-sdd-filename? filename))))))

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
   (let [before (u/curr-millis)
         dest (try
                (FileUtils/forceMkdir dest)
                dest
                (catch java.io.IOException e
                  (log/error e "Extract dest exists as file" dest "using path without extension")
                  (let [new-dest (file (parent-file dest) (without-extension (filename dest)))]
                    (when (exists? new-dest)
                      (log/warn "Deleting existing extract dest" new-dest)
                      (raynes-fs/delete new-dest))
                    new-dest)))
         source-dest {:source f
                      :dest dest}]
     (when-not f
       (throw (ex-info "No source dir to extract!" source-dest)))
     (when-not dest
       (throw (ex-info "No dest dir to extract!" source-dest)))
     (log/info "Extracting" f "to" dest)
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
     (log/info "Finished extracting" f "to" dest "in" (- (u/curr-millis) before) "ms")
     dest)))


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
  '104.0.1-1553-gd3c0012 maintenance'.

  Also for older versions, which print as 'Spring 0.79.1.2 (0.79.1.2-0-gbb45722{@}-cmake-tdm)' it
  strips 'Spring' and the detailed version in parens, leaving '0.79.1.2'."
  [sync-version]
  (cond
    (string/blank? sync-version)
    ""
    (= sync-version (string/replace sync-version #"[^\d]" ""))
    (str sync-version ".0")
    :else
    (if-let [[_all version] (re-find #"^Spring ([^\s]+) \(.*\)$" sync-version)]
      version
      sync-version)))

(defn sync-version-exe
  ([engine-exe]
   (sync-version-exe engine-exe "--sync-version"))
  ([engine-exe version-flag]
   (let [
         _ (set-executable engine-exe)
         command [(canonical-path engine-exe) version-flag]
         ^"[Ljava.lang.String;" cmdarray (into-array String command)
         runtime (Runtime/getRuntime)
         process (.exec runtime cmdarray)]
     (.waitFor process 1000 java.util.concurrent.TimeUnit/MILLISECONDS)
     (let [output (slurp (.getInputStream process))
           sync-version (-> output string/split-lines last string/trim)]
       (log/info "Discovered sync-version of" engine-exe "is" (str "'" sync-version "'"))
       sync-version))))

(defn sync-version [engine-dir]
  (let [headless-exe (file engine-dir (spring-headless-executable))
        spring-exe (file engine-dir (spring-executable))]
    (try
      (sync-version-exe headless-exe)
      (catch Exception e
        (log/error e "Error reading engine version from" headless-exe "falling back on" spring-exe)
        (sync-version-exe spring-exe "--version")))))


(defn engines-dir
  [root]
  (io/file root "engine"))

(defn spring-engine-dir? [dir]
  (and (is-directory? dir)
       (is-file? (io/file dir (spring-executable)))))
       ;(is-file? (io/file dir (spring-headless-executable)))))

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

(defn move
  "Move a file from source to dest."
  ([source dest]
   (move source dest nil))
  ([source dest {:keys [copy-options]
                 :or {copy-options
                      [StandardCopyOption/COPY_ATTRIBUTES
                       StandardCopyOption/REPLACE_EXISTING]}}]
   (if (exists? source)
     (let [^"[Ljava.nio.file.CopyOption;" options (into-array ^CopyOption copy-options)]
       (log/info "Moving" source "to" dest "options" copy-options)
       (when (exists? dest)
         (log/warn "Deleting existing dest" dest)
         (raynes-fs/delete dest))
       (FileUtils/moveFile source dest options))
     (log/warn "Source for move does not exist" source))))

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
    {
     :filename (filename f)
     :canonical-path (canonical-path f)
     :exists (exists? f)
     :is-directory (is-directory? f)
     :last-modified (last-modified f)}
    (log/warn (ex-info "stacktrace" {}) "Attempt to update file cache for nil file")))

(defn file-cache-by-path [statuses]
  (->> statuses
       (filter some?)
       (map (juxt :canonical-path identity))
       (into {})))

(defn list-descendants-cache [file-cache ^File dir]
  (when-let [path (canonical-path dir)]
    (->> file-cache
         vals
         (filter :canonical-path)
         (filterv (comp #(string/starts-with? % path) :canonical-path)))))


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


(defn spring-roots [{:keys [spring-isolation-dir servers]}]
  (set
    (filter some?
      (concat
        [spring-isolation-dir]
        (map
          (comp file :spring-isolation-dir second)
          servers)))))


(defn replay-sources [{:keys [extra-replay-sources] :as state}]
  (let [all-spring-roots (spring-roots state)]
    (concat
      (mapv
        (fn [spring-root]
          {
           :file (replays-dir spring-root)
           :builtin true})
        all-spring-roots)
      extra-replay-sources)))


; map

(defn read-zip-smf
  ([^ZipFile zf ^ZipEntry smf-entry]
   (read-zip-smf zf smf-entry nil))
  ([^ZipFile zf ^ZipEntry smf-entry opts]
   (let [smf-path (.getName smf-entry)
         smf-data (smf/decode-map (.getInputStream zf smf-entry) opts)]
     {:map-name (map-name smf-path)
      :smf (assoc smf-data ::source smf-path)})))

(defn parse-mapinfo [^java.io.File file s path]
  (try
    (let [mapinfo (lua/read-mapinfo s)]
      {:mapinfo (assoc mapinfo ::source path)
       :map-name (string/trim
                   (str (:name mapinfo)
                        (when-let [version (:version mapinfo)]
                          (when-not (string/ends-with? (:name mapinfo) (string/trim version))
                            (str " " version)))))})
    (catch Exception e
      (log/error e "Failed to parse mapinfo.lua from" file))))

(defn parse-mapoptions [^File file s path]
  (try
    {:mapoptions (lua/read-mapinfo s)}
    (catch Exception e
      (log/error e "Failed to parse mapoptions.lua from" file path))))

(defn read-zip-map
  ([^java.io.File file]
   (read-zip-map file nil))
  ([^java.io.File file opts]
   (with-open [zf (new ZipFile file)]
     (let [entry-seq (->> (.entries zf)
                          enumeration-seq)]
       (merge
         (when-let [^ZipEntry smf-entry
                    (->> entry-seq
                         (filter (comp #(string/ends-with? % ".smf") string/lower-case #(.getName ^ZipEntry %)))
                         first)]
           (read-zip-smf zf smf-entry opts))
         (when-let [^ZipEntry mapinfo-entry
                    (->> entry-seq
                         (filter (comp #{"mapinfo.lua"} string/lower-case #(.getName ^ZipEntry %)))
                         first)]
           (parse-mapinfo file (slurp (.getInputStream zf mapinfo-entry)) (.getName mapinfo-entry)))
         (when-let [^ZipEntry smd-entry
                    (->> entry-seq
                         (filter (comp #(string/ends-with? % ".smd") string/lower-case #(.getName ^ZipEntry %)))
                         first)]
           (let [smd (when-let [map-data (slurp (.getInputStream zf smd-entry))]
                       (spring-script/parse-script map-data))]
             {:smd (assoc smd ::source (.getName smd-entry))}))
         (when-let [^ZipEntry mapoptions-entry
                    (->> entry-seq
                         (filter (comp #{"mapoptions.lua"} string/lower-case #(.getName ^ZipEntry %)))
                         first)]
           (parse-mapoptions file (slurp (.getInputStream zf mapoptions-entry)) (.getName mapoptions-entry))))))))


(defrecord SequentialOutToBaos [^ByteArrayOutputStream baos]
  ISequentialOutStream
  (write [_this data]
    (log/trace "got" (count data) "bytes")
    (.write baos data 0 (count data))
    (count data)))

(defn slurp-7z-item [^ISimpleInArchiveItem item]
  (with-open [baos (ByteArrayOutputStream.)]
    (when (.extractSlow item
            (SequentialOutToBaos. baos))
      (u/bytes->str (.toByteArray baos)))))

(defn slurp-7z-item-bytes [^ISimpleInArchiveItem item]
  (with-open [baos (ByteArrayOutputStream.)]
    (when (.extractSlow item
            (SequentialOutToBaos. baos))
      (.toByteArray baos))))

(defn read-7z-smf-bytes
  [smf-path smf-bytes opts]
  (let [smf-data (smf/decode-map (io/input-stream smf-bytes) opts)]
    {:map-name (map-name smf-path)
     :smf (assoc smf-data ::source smf-path)}))

(defn parse-extracted-7z-map [extracted opts]
  (merge
    (when-let [[path smf-bytes]
               (->> extracted
                    (filter (comp #(string/ends-with? % ".smf") first))
                    first)]
      (read-7z-smf-bytes path smf-bytes opts))
    (when-let [[path mapinfo-bytes]
               (->> extracted
                    (filter (comp #{"mapinfo.lua"}
                                  string/lower-case
                                  first))
                    first)]
      (parse-mapinfo file (slurp mapinfo-bytes) path))
    (when-let [[path mapoptions-bytes]
               (->> extracted
                    (filter (comp #{"mapoptions.lua"}
                                  string/lower-case
                                  first))
                    first)]
      (parse-mapoptions file (slurp mapoptions-bytes) path))
    (when-let [[path smd-bytes]
               (->> extracted
                    (filter (comp #(string/ends-with? % ".smd") first))
                    first)]
      (let [smd (spring-script/parse-script (slurp smd-bytes))]
        {:smd (assoc smd ::source path)}))))

(defn filter-7z-map-entry? [path]
  (let [
        path-lc (string/lower-case path)]
    (or
      (string/ends-with? path-lc ".smd")
      (string/ends-with? path-lc ".smf")
      (string/ends-with? path-lc "mapinfo.lua")
      (string/ends-with? path-lc "mapoptions.lua"))))


(defrecord ExtractCallback [^IInArchive archive callback-state extracted-state]
  IArchiveExtractCallback
  (getStream [_this index _extract-ask-mode]
    (swap! callback-state assoc :index index)
    (try
      (let [path (.getProperty archive index PropID/PATH)
            is-folder (.getProperty archive index PropID/IS_FOLDER)]
        (when-not is-folder
          (log/debug "Stream for index" index "path" path)
          (let [baos (ByteArrayOutputStream.)] ; not with-open
            (swap! callback-state assoc :stream baos :path path)
            (SequentialOutToBaos. baos))))
      (catch Throwable e
        (log/error e "Error getting stream for item" index))))
  (prepareOperation [_this extract-ask-mode]
    (swap! callback-state assoc :extract-ask-mode extract-ask-mode)
    (log/trace "preparing" extract-ask-mode))
  (setOperationResult [_this extract-operation-result]
    (let [cs @callback-state]
      (when-let [^ByteArrayOutputStream output-stream (:stream cs)]
        (try
          (.close output-stream)
          (let [ba (.toByteArray output-stream)]
            (swap! extracted-state assoc (:path cs) ba))
          (catch Exception e
            (log/error e "Error closing output stream")
            (throw (SevenZipException. "Error closing output stream"))))))
    (swap! callback-state assoc :operation-result extract-operation-result)
    (log/trace "result" extract-operation-result))
  (setTotal [_this total]
    (swap! callback-state assoc :total total)
    (log/trace "total" total))
  (setCompleted [_this complete]
    (swap! callback-state assoc :complete complete)
    (log/trace "completed" complete)))


(defn read-7z-map-fast
  ([^java.io.File file]
   (read-7z-map-fast file nil))
  ([^java.io.File file opts]
   (with-open [raf (new RandomAccessFile file "r")
               rafis (new RandomAccessFileInStream raf)
               archive (SevenZip/openInArchive nil rafis)]
     (log/debug file "is of format" (.getArchiveFormat archive)
                "and has" (.getNumberOfItems archive) "items")
     (let [n (.getNumberOfItems archive)
           ids (filter
                 (fn [id]
                   (let [path (.getProperty archive id PropID/PATH)]
                     (filter-7z-map-entry? path)))
                 (take n (iterate inc 0)))
           extracted-state (atom {})
           callback-state (atom {})]
       (.extract archive
                 (int-array ids)
                 false
                 (ExtractCallback. archive callback-state extracted-state))
       (parse-extracted-7z-map @extracted-state opts)))))

(defn read-7z-map-apache
  ([^java.io.File file]
   (read-7z-map-fast file nil))
  ([^java.io.File file opts]
   (let [options-builder (doto (SevenZFileOptions/builder)
                           (.withTryToRecoverBrokenArchives true)
                           (.withUseDefaultNameForUnnamedEntries true))
         ^SevenZFileOptions options (.build options-builder)
         sevenz-file (new SevenZFile file options)
         extracted-state (atom {})]
     (loop []
       (when-let [^SevenZArchiveEntry entry (try
                                              (.getNextEntry sevenz-file)
                                              (catch Exception e
                                                (log/error e "Error getting next entry")
                                                :invalid))]
         (if (not= :invalid entry)
           (let [path (.getName entry)]
             (when (filter-7z-map-entry? path)
               (let [
                     size (.getSize entry)
                     content (byte-array size)]
                 (log/info "Reading" path "from" file "size" size)
                 (.read sevenz-file content 0 size)
                 (when-not (.isDirectory entry)
                   (swap! extracted-state assoc path content)))))
           (log/warn "Skiping invalid 7z entry in" file))
         (recur)))
     (parse-extracted-7z-map @extracted-state opts))))

(defn read-smf-file
  ([smf-file]
   (read-smf-file smf-file nil))
  ([smf-file opts]
   (let [smf-path (canonical-path smf-file)
         smf-data (smf/decode-map (io/input-stream smf-file) opts)]
     {:map-name (map-name smf-path)
      :smf (assoc smf-data ::source smf-path)})))

(defn read-map-directory
  ([file]
   (read-map-directory [file nil]))
  ([file opts]
   (let [all-files (file-seq file)]
     (merge
       (when-let [smf-file (->> all-files
                                (filter (comp #(string/ends-with? % ".smf") string/lower-case filename))
                                (sort-by (comp not (partial child? file)))
                                first)]
         (read-smf-file smf-file opts))
       (when-let [mapinfo-file (->> all-files
                                    (filter (comp #{"mapinfo.lua"} string/lower-case filename))
                                    (sort-by (comp not (partial child? file)))
                                    first)]
         (let [content (slurp mapinfo-file)
               path (canonical-path mapinfo-file)]
           (parse-mapinfo file content path)))
       (when-let [mapoptions-file (->> all-files
                                       (filter (comp #{"mapoptions.lua"} string/lower-case filename))
                                       (sort-by (comp not (partial child? file)))
                                       first)]
         (let [content (slurp mapoptions-file)
               path (canonical-path mapoptions-file)]
           (parse-mapoptions file content path)))
       (when-let [smd-file (->> all-files
                                (filter (comp #(string/ends-with? % ".smd") string/lower-case filename))
                                (sort-by (comp not (partial child? file)))
                                first)]
         (let [smd (when-let [map-data (slurp (io/input-stream smd-file))]
                     (spring-script/parse-script map-data))]
           {:smd (assoc smd ::source (canonical-path smd-file))}))))))

(defn read-map-data
  ([^File file]
   (read-map-data file nil))
  ([^File file opts]
   (let [filename (filename file)]
     (log/info "Loading map" file)
     (merge
       {:file file}
       (try
         (cond
           (and (is-file? file) (string/ends-with? filename ".sdz"))
           (read-zip-map file opts)
           (and (is-file? file) (string/ends-with? filename ".sd7"))
           (try
             (read-7z-map-fast file opts)
             (catch Exception e
               (log/error e "Error reading map with native 7zip, falling back on Apache commons-compress")
               (read-7z-map-apache file opts)))
           (and (is-directory? file) (is-sdd-filename? filename))
           (read-map-directory file opts)
           :else
           (do
             (log/error "No method to read map file" file opts)
             {:error true}))
         (catch Exception e
           (log/warn e "Error reading map data for" filename)
           {:error true}))))))


; mod

(defn mod-files
  [root]
  (spring-files-and-dirs (io/file root "games")))

(defn slurp-zip-entry [^ZipFile zip-file entries entry-filename-lowercase]
  (when-let [entry
             (->> entries
                  (filter (comp #{entry-filename-lowercase} string/lower-case #(.getName ^ZipEntry %)))
                  first)]
    (slurp (.getInputStream zip-file entry))))

(defn read-mod-zip-file
  ([^java.io.File file]
   (read-mod-zip-file file nil))
  ([^java.io.File file {:keys [modinfo-only]}]
   (with-open [zf (new ZipFile file)]
     (let [entries (enumeration-seq (.entries zf))
           try-entry-lua (fn [filename]
                           (try
                             (when-let [slurped (slurp-zip-entry zf entries filename)]
                               (lua/read-modinfo slurped))
                             (catch Exception e
                               (log/trace e "Error loading" filename "from" file)
                               (log/warn "Error loading" filename "from" file))))
           try-entry-script (fn [filename]
                              (try
                                (when-let [slurped (slurp-zip-entry zf entries filename)]
                                  (spring-script/parse-script slurped))
                                (catch Exception e
                                  (log/trace e "Error loading" filename "from" file)
                                  (log/warn "Error loading" filename "from" file))))]
       (merge
         {:file file
          :modinfo (try-entry-lua "modinfo.lua")
          ::source :archive}
         (when-not modinfo-only
           {:modoptions (try-entry-lua "modoptions.lua")
            :engineoptions (try-entry-lua "engineoptions.lua")
            :luaai (try-entry-lua "luaai.lua")
            :validais (try-entry-lua "validais.lua")
            ; https://stackoverflow.com/a/44387973
            :sidedata (or (try-entry-lua "gamedata/sidedata.lua")
                          (try-entry-script "gamedata/sidedata.tdf")
                          (u/postprocess-byar-units-en
                            (try-entry-lua "language/units_en.lua")))}))))))

(defn read-7z-mod
  ([^java.io.File file]
   (read-7z-mod file nil))
  ([^java.io.File file {:keys [modinfo-only]}]
   (with-open [raf (new RandomAccessFile file "r")
               rafis (new RandomAccessFileInStream raf)
               archive (SevenZip/openInArchive nil rafis)]
     (log/debug file "is of format" (.getArchiveFormat archive)
                "and has" (.getNumberOfItems archive) "items")
     (let [n (.getNumberOfItems archive)
           ids (filter
                 (fn [id]
                   (let [path (.getProperty archive id PropID/PATH)
                         path-lc (string/lower-case path)]
                     (or
                       (string/ends-with? path-lc "engineoptions.lua")
                       (string/ends-with? path-lc "luaai.lua")
                       (string/ends-with? path-lc "validais.lua")
                       (string/ends-with? path-lc "modinfo.lua")
                       (string/ends-with? path-lc "modoptions.lua")
                       (string/ends-with? path-lc "sidedata.lua")
                       (string/ends-with? path-lc "sidedata.tdf")
                       (string/ends-with? path-lc "units_en.lua"))))
                 (take n (iterate inc 0)))
           extracted-state (atom {})
           callback-state (atom {})]
       (.extract archive
                 (int-array ids)
                 false
                 (ExtractCallback. archive callback-state extracted-state))
       (let [extracted @extracted-state
             try-entry-lua (fn [filename]
                             (try
                               (if-let [[_path file-bytes]
                                        (->> extracted
                                             (filter (comp #(string/ends-with? % filename) string/lower-case first))
                                             first)]
                                 (lua/read-modinfo (u/bytes->str file-bytes))
                                 (log/warn "Mod file" filename "not found in" file))
                               (catch Exception e
                                 (log/trace e "Error loading" filename "from" file)
                                 (log/warn "Error loading" filename "from" file))))
             try-entry-script (fn [filename]
                                (try
                                  (if-let [[_path file-bytes]
                                           (->> extracted
                                                (filter (comp #(string/ends-with? % filename) string/lower-case first))
                                                first)]
                                    (spring-script/parse-script (u/bytes->str file-bytes))
                                    (log/warn "Mod file" filename "not found in" file))
                                  (catch Exception e
                                    (log/trace e "Error loading" filename "from" file)
                                    (log/warn "Error loading" filename "from" file))))]
         (merge
           {:file file
            :modinfo (try-entry-lua "modinfo.lua")
            ::source :archive}
           (when-not modinfo-only
             {:modoptions (try-entry-lua "modoptions.lua")
              :engineoptions (try-entry-lua "engineoptions.lua")
              :luaai (try-entry-lua "luaai.lua")
              :validais (try-entry-lua "validais.lua")
              :sidedata (or (try-entry-lua (join "gamedata" "sidedata.lua"))
                            (try-entry-script (join "gamedata" "sidedata.tdf"))
                            (u/postprocess-byar-units-en
                              (try-entry-lua (join "language" "units_en.lua"))))})))))))

(defn read-mod-directory
  ([^java.io.File file]
   (read-mod-directory file nil))
  ([^java.io.File file {:keys [modinfo-only]}]
   (let [try-file-lua (fn [filename]
                        (try
                          (when-let [slurped (slurp (io/file file filename))]
                            (lua/read-modinfo slurped))
                          (catch java.io.FileNotFoundException _e
                            (log/warn "Mod" file "is missing expected file" filename))
                          (catch Exception e
                            (log/trace e "Error loading" filename "from" file)
                            (log/warn "Error loading" filename "from" file))))]
     (merge
       {:file file
        :modinfo (try-file-lua "modinfo.lua")
        :git-commit-id (try
                         (git/latest-id file)
                         (catch java.io.FileNotFoundException _e
                           (log/warn "Not a git repository at" file))
                         (catch Exception e
                           (log/error e "Error loading git commit id")))
        ::source :directory}
       (when-not modinfo-only
         {:modoptions (try-file-lua "modoptions.lua")
          :engineoptions (try-file-lua "engineoptions.lua")
          :luaai (try-file-lua "luaai.lua")
          :validais (try-file-lua "validais.lua")
          :sidedata (or (try-file-lua "gamedata/sidedata.lua")
                        (try-file-lua "gamedata/sidedata.tdf")
                        (u/postprocess-byar-units-en
                          (try-file-lua "language/units_en.lua")))})))))

(defn read-mod-file
  ([^java.io.File file]
   (read-mod-file file nil))
  ([^java.io.File file opts]
   (let [filename (filename file)]
     (cond
       (and (is-directory? file) (is-sdd-filename? filename))
       (read-mod-directory file opts)
       (string/ends-with? filename ".sdz")
       (read-mod-zip-file file opts)
       (string/ends-with? filename ".sd7")
       (read-7z-mod file opts)
       :else
       (log/warn "Unknown mod file type" file)))))

; engine

(def engine-data-version 1)

(defn is-current-engine-data? [engine-data]
  (boolean
    (when-let [version (:engine-data-version engine-data)]
      (= engine-data-version version))))

(defn engine-bots
  [engine-file]
  (or
    (try
      (when engine-file
        (let [ai-skirmish-dir (io/file engine-file "AI" "Skirmish")
              ai-dirs (some->> (list-files ai-skirmish-dir)
                               seq
                               (filter is-directory?))]
          (mapcat
            (fn [^java.io.File ai-dir]
              (log/info "Loading AI from" ai-dir)
              (->> (list-files ai-dir)
                   (filter is-directory?)
                   (map
                     (fn [^java.io.File version-dir]
                       (log/info "Loading AI version from" version-dir)
                       (let [options-file (->> (list-files version-dir)
                                               (filter is-file?)
                                               (filter (comp #{"aioptions.lua"} string/lower-case filename))
                                               first)]
                         (log/info "Loading AI options from" options-file)
                         {:bot-name (filename ai-dir)
                          :bot-version (filename version-dir)
                          :bot-options (try
                                         (let [contents (slurp options-file)]
                                           (lua/read-modinfo contents))
                                         (catch Exception e
                                           (log/trace e "Error loading AI options from" options-file)
                                           (log/warn "Error loading AI options from" options-file)))})))))
            ai-dirs)))
      (catch Exception e
        (log/error e "Error loading bots")))
    []))

(defn engine-data [^File engine-dir]
  (merge
    {:file engine-dir}
    (try
      (if-let [sync-version (sync-version engine-dir)]
        {
         :sync-version sync-version
         :engine-version (sync-version-to-engine-version sync-version)
         :engine-bots (engine-bots engine-dir)
         :engine-data-version engine-data-version}
        {
         :error true})
      (catch Exception e
        (log/error e "Error reading engine data for" engine-dir)
        {
         :error true}))))


(defn delete-skylobby-update-jars []
  (let [skylobby-jars (->> (download-dir)
                           list-files
                           (filter is-file?)
                           (filter (comp (partial re-find #"skylobby.*\.jar$") filename)))]
    (log/info "Deleting skylobby update jars:" (pr-str skylobby-jars))
    (doseq [jar skylobby-jars]
      (raynes-fs/delete jar))
    skylobby-jars))

(defn delete-rapid-dir [spring-root]
  (let [rapid-dir (io/file spring-root "rapid")]
    (if (and (is-directory? rapid-dir) (exists? rapid-dir))
      (do
        (log/info "Deleting corrupt rapid dir:" rapid-dir)
        (FileUtils/deleteDirectory rapid-dir))
      (log/error "Not a valid directory:" rapid-dir))))


(defn nippy-filename [edn-filename]
  (when edn-filename
    (string/replace edn-filename #"\.edn$" ".bin")))


(defn spit-app-edn
  "Writes the given data as edn to the given file in the application directory."
  ([data filename]
   (spit-app-edn data filename nil))
  ([data filename {:keys [nippy pretty]}]
   (let [file (config-file filename)]
     (make-parent-dirs file))
   (if nippy
     (let [file (config-file (nippy-filename filename))]
       (log/info "Saving nippy data to" file)
       (try
         (nippy/freeze-to-file file data)
         (catch Exception e
           (log/error e "Error saving nippy to" file))))
     (let [output (if pretty
                    (with-out-str (pprint (if (map? data)
                                            (into (sorted-map) data)
                                            data)))
                    (pr-str data))
           parsable (try
                      (edn/read-string {:readers u/custom-readers} output)
                      true
                      (catch Exception e
                        (log/error e "Config EDN for" filename "does not parse, keeping old file")))
           file (config-file (if parsable
                               filename
                               (str filename ".bad")))]
       (log/info "Spitting edn to" file)
       (spit file output)))))
