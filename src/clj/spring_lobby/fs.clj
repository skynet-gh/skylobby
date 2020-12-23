(ns spring-lobby.fs
  (:require
    [byte-streams]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [com.climate.claypoole :as cp]
    [spring-lobby.fs.smf :as smf]
    [spring-lobby.git :as git]
    [spring-lobby.lua :as lua]
    [spring-lobby.spring.script :as spring-script]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (java.io ByteArrayOutputStream File FileOutputStream RandomAccessFile)
    (java.nio.file CopyOption Files Path StandardCopyOption)
    (java.util.zip ZipEntry ZipFile)
    (javax.imageio ImageIO)
    (net.sf.sevenzipjbinding IArchiveExtractCallback ISequentialOutStream PropID SevenZip SevenZipException)
    (net.sf.sevenzipjbinding.impl RandomAccessFileInStream)
    (net.sf.sevenzipjbinding.simple ISimpleInArchiveItem)
    (org.apache.commons.io FilenameUtils FileUtils)))


(set! *warn-on-reflection* true)


(defn init-7z! []
  (SevenZip/initSevenZipFromPlatformJAR))


(def config-filename "config.edn")
(def maps-filename "maps.edn")


(defn canonical-path [^File f]
  (when f
    (.getCanonicalPath f)))

; TODO always use canonical-path
(defn absolute-path [^File f]
  (when f
    (.getAbsolutePath f)))

(defn filename [^File f]
  (when f
    (.getName f)))

(defn exists [^File f]
  (when f
    (.exists f)))

(defn is-directory [^File f]
  (when f
    (.isDirectory f)))

(defn is-file? [^File f]
  (when f
    (.isFile f)))

(defn parent-file ^File [^File f]
  (when f
    (.getParentFile f)))

(defn list-files [^File f]
  (when f
    (.listFiles f)))

(defn to-path [^File f]
  (when f
    (.toPath f)))

(defn size [^File f]
  (when f
    (when-let [path (.toPath f)]
      (Files/size path))))


(defn os-name []
  (System/getProperty "os.name"))

(defn os-version []
  (System/getProperty "os.version"))

(defn user-home []
  (System/getProperty "user.home"))

(defn user-name []
  (System/getProperty "user.name"))

(defn sys-data []
  {:os-name (os-name)
   :os-version (os-version)
   :user-home (user-home)
   :user-name (user-name)})

(defn windows?
  ([]
   (windows? (sys-data)))
  ([{:keys [os-name]}]
   (string/includes? os-name "Windows")))

(defn wsl?
  "Returns true if this system appears to be the Windows Subsystem for Linux."
  ([]
   (wsl? (sys-data)))
  ([sys-data]
   (let [{:keys [os-name os-version]} sys-data]
     (and
       (string/includes? os-name "Linux")
       (or
         (string/includes? os-version "Microsoft") ; WSL
         (string/includes? os-version "microsoft")))))) ; WSL 2

(defn wsl-or-windows? []
  (or (windows?) (wsl?)))

(defn platform
  ([]
   (platform (sys-data)))
  ([{:keys [os-name]}]
   (if (and os-name
            (string/includes? os-name "Linux")
            (not (wsl?)))
     "linux64"
     "win32")))

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


(defn envp
  "Returns environment variables to pass to spring for this system."
  []
  nil
  #_
  (if (string/includes? (os-name) "Linux")
    (into-array
      ^String
      ["LD_LIBRARY_PATH=/var/lib/snapd/lib/gl:/var/lib/snapd/lib/gl32:/var/lib/snapd/void:/snap/springlobby-nsg/common/lib/x86_64-linux-gnu:/snap/springlobby-nsg/common/usr/lib/x86_64-linux-gnu:/snap/springlobby-nsg/common/usr/lib/x86_64-linux-gnu/pulseaudio::/snap/springlobby-nsg/common/lib:/snap/springlobby-nsg/common/usr/lib:/snap/springlobby-nsg/common/lib/x86_64-linux-gnu:/snap/springlobby-nsg/common/usr/lib/x86_64-linux-gnu:/snap/springlobby-nsg/common/usr/lib/x86_64-linux-gnu/dri:/var/lib/snapd/lib/gl:/snap/springlobby-nsg/common/usr/lib/x86_64-linux-gnu/pulseaudio"])
    nil))


(defn executable [common-name]
  (str
    common-name
    (when (wsl-or-windows?)
      ".exe")))

(defn spring-executable []
  (executable "spring"))

(defn spring-headless-executable []
  (executable "spring-headless"))


(defn bar-root
  "Returns the root directory for BAR"
  ^File []
  (let [{:keys [os-name user-name user-home] :as sys-data} (sys-data)]
    (cond
      (string/includes? os-name "Linux")
      (if (wsl? sys-data)
        (io/file "/mnt" "c" "Users" user-name "AppData" "Local" "Programs" "Beyond-All-Reason" "data")
        (let [snap-dir (io/file user-home "snap" "springlobby-nsg" "common" ".spring")]
          (if (.exists snap-dir)
            snap-dir
            (io/file user-home ".spring"))))
      (string/includes? os-name "Windows")
      (io/file user-home "AppData" "Local" "Programs" "Beyond-All-Reason" "data")
      :else
      (throw (ex-info "Unable to determine Spring root for this system"
                      {:sys-data sys-data})))))

(defn spring-root
  "Returns the root directory for Spring"
  ^File []
  (let [{:keys [os-name user-name user-home] :as sys-data} (sys-data)]
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
      (throw (ex-info "Unable to determine Spring root for this system"
                      {:sys-data sys-data})))))

(defn springlobby-root
  "Returns the root directory for Spring"
  []
  (let [{:keys [os-name user-name user-home] :as sys-data} (sys-data)]
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
      (throw (ex-info "Unable to determine Spring root for this system"
                      {:sys-data sys-data})))))

(defn app-root
  "Returns the root directory for this application"
  []
  (let [{:keys [os-name user-name user-home] :as sys-data} (sys-data)]
    (cond
      (string/includes? os-name "Linux")
      (if (wsl? sys-data)
        (io/file "/mnt" "c" "Users" user-name ".alt-spring-lobby")
        (io/file user-home ".alt-spring-lobby"))
      (string/includes? os-name "Windows")
      (io/file user-home ".alt-spring-lobby")
      :else
      (throw (ex-info "Unable to determine app root for this system"
                      {:sys-data sys-data})))))

(defn download-dir ^File
  []
  (io/file (app-root) "download"))

(defn isolation-dir
  "Returns the isolation dir for spring in this app, usually $HOME/.alt-spring-lobby/spring"
  ^File
  []
  (io/file (app-root) "spring"))

(defn replays-dir
  ([]
   (replays-dir (isolation-dir)))
  ([root]
   (io/file root "demos")))

(defn replay-files
  ([]
   (replay-files (isolation-dir)))
  ([root]
   (->> (replays-dir root)
        list-files
        (filter #(.isFile %))
        (filter (comp #(string/ends-with? % ".sdfz") filename)))))

(defn map-files
  ([]
   (map-files (isolation-dir)))
  ([root]
   (->> (io/file root "maps")
        file-seq
        (filter is-file?)
        (filter #(or (string/ends-with? (filename %) ".sd7")
                     (string/ends-with? (filename %) ".sdz"))))))

#_
(map-files)

(defn- extract-7z
  ([^File f]
   (let [fname (.getName f)
         dir (if (string/includes? fname ".")
               (subs fname 0 (.lastIndexOf fname "."))
               fname)
         dest (io/file (.getParentFile f) dir)]
     (extract-7z f dest)))
  ([^File f ^File dest]
   (with-open [raf (new RandomAccessFile f "r")
               rafis (new RandomAccessFileInStream raf)
               archive (SevenZip/openInArchive nil rafis)
               simple (.getSimpleInterface archive)]
     (log/trace archive "has" (.getNumberOfItems archive) "items")
     (doseq [^ISimpleInArchiveItem item (.getArchiveItems simple)]
       (let [path (.getPath item)
             to (io/file dest path)]
         (try
           (when-not (.isFolder item)
             (when-not (.exists to)
               (let [parent (.getParentFile to)]
                 (.mkdirs parent))
               (log/info "Extracting" path "to" to)
               (with-open [fos (FileOutputStream. to)]
                 (let [res (.extractSlow item
                             (reify ISequentialOutStream
                               (write [this data]
                                 (.write fos data 0 (count data))
                                 (count data))))]
                   (log/info "Extract result for" to res)))))
           (catch Exception e
             (log/warn e "Error extracting"))))))
   (log/info "Finished extracting" f "to" dest)))


(defn- close-7z-stream [callback-state]
  (when-let [output-stream (:stream @callback-state)]
    (try
      (.close output-stream)
      (catch Exception e
        (log/error e "Error closing output stream")
        (throw (SevenZipException. "Error closing output stream"))))))

(defn extract-7z-fast
  ([^File f]
   (let [fname (filename f)
         dir (if (string/includes? fname ".")
               (subs fname 0 (.lastIndexOf fname "."))
               fname)
         dest (io/file (parent-file f) dir)]
     (extract-7z-fast f dest)))
  ([^File f ^File dest]
   (let [before (u/curr-millis)]
     (log/info "Extracting" f "to" dest)
     (FileUtils/forceMkdir dest)
     (with-open [raf (new RandomAccessFile f "r")
                 rafis (new RandomAccessFileInStream raf)
                 archive (SevenZip/openInArchive nil rafis)]
       (log/debug f "is of format" (.getArchiveFormat archive)
                  "and has" (.getNumberOfItems archive) "items")
       ; http://sevenzipjbind.sourceforge.net/javadoc/net/sf/sevenzipjbinding/IInArchive.html#extract(int[],%20boolean,%20net.sf.sevenzipjbinding.IArchiveExtractCallback)
       (let [callback-state (atom {})]
         (.extract archive
                   nil ; all items
                   false ; not test mode
                   ; http://sevenzipjbind.sourceforge.net/javadoc/net/sf/sevenzipjbinding/IArchiveExtractCallback.html
                   ; https://gist.github.com/borisbrodski/6120309
                   (reify IArchiveExtractCallback
                     (getStream [this index extract-ask-mode]
                       (swap! callback-state assoc :index index)
                       (close-7z-stream callback-state)
                       (try
                         (let [path (.getProperty archive index PropID/PATH)
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
                                 (reify ISequentialOutStream
                                   (write [this data]
                                     (.write fos data 0 (count data))
                                     (count data)))))))
                         (catch Throwable e
                           (log/error e "Error getting stream for item" index))))
                     (prepareOperation [this extract-ask-mode]
                       (swap! callback-state assoc :extract-ask-mode extract-ask-mode)
                       (log/trace "preparing" extract-ask-mode))
                     (setOperationResult [this extract-operation-result]
                       (close-7z-stream callback-state)
                       (swap! callback-state assoc :operation-result extract-operation-result)
                       (log/trace "result" extract-operation-result))
                     (setTotal [this total]
                       (swap! callback-state assoc :total total)
                       (log/trace "total" total))
                     (setCompleted [this complete]
                       (swap! callback-state assoc :complete complete)
                       (log/trace "completed" complete))))))
     (log/info "Finished extracting" f "to" dest "in" (- (u/curr-millis) before) "ms"))))


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
        _ (.setExecutable engine-exe true)
        command [(canonical-path engine-exe) "--sync-version"]
        ^"[Ljava.lang.String;" cmdarray (into-array String command)
        runtime (Runtime/getRuntime)
        process (.exec runtime cmdarray)]
    (.waitFor process 1000 java.util.concurrent.TimeUnit/MILLISECONDS)
    (let [sync-version (string/trim (slurp (.getInputStream process)))]
      (log/info "Discovered sync-version of" engine-exe "is" (str "'" sync-version "'"))
      sync-version)))


(defn engines-dir
  ([]
   (engines-dir (isolation-dir)))
  ([root]
   (io/file root "engine")))

(defn engine-dirs
  ([]
   (engine-dirs (isolation-dir)))
  ([root]
   (->> (list-files (io/file root "engine"))
        seq
        (filter is-directory))))

(defn engine-data [^File engine-dir]
  (let [sync-version (sync-version engine-dir)]
    {:file engine-dir
     :sync-version sync-version
     :engine-version (sync-version-to-engine-version sync-version)}))


(defn slurp-zip-entry [^ZipFile zip-file entries entry-filename-lowercase]
  (when-let [entry
             (->> entries
                  (filter (comp #{entry-filename-lowercase} string/lower-case #(.getName ^ZipEntry %)))
                  first)]
    (slurp (.getInputStream zip-file entry))))


(defn mods-dir
  ([]
   (mods-dir (isolation-dir)))
  ([root]
   (io/file root "games")))

(defn mod-file
  ([mod-filename]
   (mod-file (isolation-dir) mod-filename))
  ([root mod-filename]
   (when mod-filename
     (io/file (mods-dir root) mod-filename))))

(defn mod-files
  ([]
   (mod-files (isolation-dir)))
  ([root]
   (seq (list-files (io/file root "games")))))

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
                               (log/warn e "Error loading" filename "from" file))))]
       (merge
         {:file file
          :modinfo (try-entry-lua "modinfo.lua")
          ::source :archive}
         (when-not modinfo-only
           {:modoptions (try-entry-lua "modoptions.lua")
            :engineoptions (try-entry-lua "engineoptions.lua")
            :luaai (try-entry-lua "luaai.lua")}))))))

(defn read-mod-directory
  ([^java.io.File file]
   (read-mod-directory file nil))
  ([^java.io.File file {:keys [modinfo-only]}]
   (let [try-file-lua (fn [filename]
                        (try
                          (when-let [slurped (slurp (io/file file filename))]
                            (lua/read-modinfo slurped))
                          (catch Exception e
                            (log/warn e "Error loading" filename "from" file))))]
     (merge
       {:file file
        :modinfo (try-file-lua "modinfo.lua")
        :git-commit-id (try
                         (git/latest-id file)
                         (catch Exception e
                           (log/error e "Error loading git commit id")))
        ::source :directory}
       (when-not modinfo-only
         {:modoptions (try-file-lua "modoptions.lua")
          :engineoptions (try-file-lua "engineoptions.lua")
          :luaai (try-file-lua "luaai.lua")})))))

(defn read-mod-file
  ([^java.io.File file]
   (read-mod-file file nil))
  ([^java.io.File file opts]
   (cond
     (is-directory file)
     (read-mod-directory file opts)
     (string/ends-with? (filename file) ".sdz")
     (read-mod-zip-file file opts)
     :else
     (log/warn "Unknown mod file type" file))))

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
  ([]
   (maps-dir (isolation-dir)))
  ([root]
   (io/file root "maps")))

(defn map-file
  ([map-filename]
   (map-file (isolation-dir) map-filename))
  ([root map-filename]
   (when map-filename
     (io/file (maps-dir root) map-filename))))

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

(defn read-zip-smf
  ([zf ^ZipEntry smf-entry]
   (read-zip-smf zf smf-entry nil))
  ([zf ^ZipEntry smf-entry {:keys [header-only]}]
   (let [smf-path (.getName smf-entry)]
     (if header-only
       (let [header (smf/decode-map-header (.getInputStream zf smf-entry))]
         {:map-name (map-name smf-path)
          :smf (merge
                 {::source smf-path
                  :header header})})
       (let [{:keys [body header]} (smf/decode-map (.getInputStream zf smf-entry))
             {:keys [map-width map-height]} header]
         {:map-name (map-name smf-path)
          ; TODO extract only what's needed
          :smf (merge
                 {::source smf-path
                  :header header}
                 (when-let [minimap (:minimap body)]
                   {;:minimap-bytes minimap
                    :minimap-image (smf/decompress-minimap minimap)})
                 (when-let [metalmap (:metalmap body)]
                   {;:metalmap-bytes metalmap
                    :metalmap-image (smf/metalmap-image map-width map-height metalmap)}))})))))

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
             {:smd (assoc smd ::source (.getName smd-entry))})))))))


(defn slurp-7z-item [^ISimpleInArchiveItem item]
  (with-open [baos (ByteArrayOutputStream.)]
    (when (.extractSlow item
            (reify ISequentialOutStream
              (write [this data]
                (log/trace "got" (count data) "bytes")
                (.write baos data 0 (count data))
                (count data))))
      (byte-streams/convert (.toByteArray baos) String))))

(defn slurp-7z-item-bytes [^ISimpleInArchiveItem item]
  (with-open [baos (ByteArrayOutputStream.)]
    (when (.extractSlow item
            (reify ISequentialOutStream
              (write [this data]
                (log/trace "got" (count data) "bytes")
                (.write baos data 0 (count data))
                (count data))))
      (.toByteArray baos))))

(defn read-7z-smf-bytes
  [smf-path smf-bytes {:keys [header-only]}]
  (if header-only
    (let [header (smf/decode-map-header (io/input-stream smf-bytes))]
      {:map-name (map-name smf-path)
       :smf (merge
              {::source smf-path
               :header header})})
    (let [{:keys [body header]} (smf/decode-map (io/input-stream smf-bytes))
          {:keys [map-width map-height]} header]
      {:map-name (map-name smf-path)
       ; TODO extract only what's needed
       :smf (merge
              {::source smf-path
               :header header}
              (when-let [minimap (:minimap body)]
                {;:minimap-bytes minimap
                 :minimap-image (smf/decompress-minimap minimap)})
              (when-let [metalmap (:metalmap body)]
                {;:metalmap-bytes metalmap
                 :metalmap-image (smf/metalmap-image map-width map-height metalmap)}))})))

(defn read-7z-smf
  ([^ISimpleInArchiveItem smf-item]
   (read-7z-smf smf-item nil))
  ([^ISimpleInArchiveItem smf-item {:keys [header-only]}]
   (let [smf-path (.getPath smf-item)]
     (if header-only
       (let [header (smf/decode-map-header (io/input-stream (slurp-7z-item-bytes smf-item)))]
         {:map-name (map-name smf-path)
          :smf (merge
                 {::source smf-path
                  :header header})})
       (let [{:keys [body header]} (smf/decode-map (io/input-stream (slurp-7z-item-bytes smf-item)))
             {:keys [map-width map-height]} header]
         {:map-name (map-name smf-path)
          ; TODO extract only what's needed
          :smf (merge
                 {::source smf-path
                  :header header}
                 (when-let [minimap (:minimap body)]
                   {:minimap-bytes minimap
                    :minimap-image (smf/decompress-minimap minimap)})
                 (when-let [metalmap (:metalmap body)]
                   {:metalmap-bytes metalmap
                    :metalmap-image (smf/metalmap-image map-width map-height metalmap)}))})))))

(defn- read-7z-map
  ([^java.io.File file]
   (read-7z-map file nil))
  ([^java.io.File file opts]
   (with-open [raf (new RandomAccessFile file "r")
               rafis (new RandomAccessFileInStream raf)
               archive (SevenZip/openInArchive nil rafis)
               simple (.getSimpleInterface archive)]
     (merge
       (when-let [^ISimpleInArchiveItem smf-item
                  (->> (.getArchiveItems simple)
                       (filter (comp #(string/ends-with? % ".smf")
                                     string/lower-case
                                     #(.getPath ^ISimpleInArchiveItem %)))
                       first)]
         (read-7z-smf smf-item opts))
       (when-let [^ISimpleInArchiveItem mapinfo-item
                  (->> (.getArchiveItems simple)
                       (filter (comp #{"mapinfo.lua"}
                                     string/lower-case
                                     #(.getPath ^ISimpleInArchiveItem %)))
                       first)]
         (parse-mapinfo file (slurp-7z-item mapinfo-item) (.getPath mapinfo-item)))
       (when-let [^ISimpleInArchiveItem smd-item
                  (->> (.getArchiveItems simple)
                       (filter (comp #(string/ends-with? % ".smd") string/lower-case #(.getPath ^ISimpleInArchiveItem %)))
                       first)]
         (let [smd (spring-script/parse-script (slurp-7z-item smd-item))]
           {:smd (assoc smd ::source (.getPath smd-item))}))))))

#_
(time (read-7z-map (map-file "altored_divide_bar_remake_1.5.sd7")))


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
                   (let [path (.getProperty archive id PropID/PATH)
                         path-lc (string/lower-case path)]
                     (or
                       (string/ends-with? path-lc ".smd")
                       (string/ends-with? path-lc ".smf")
                       (string/ends-with? path-lc "mapinfo.lua"))))
                 (take n (iterate inc 0)))
           extracted-state (atom {})
           callback-state (atom {})]
       (.extract archive
                 (int-array ids)
                 false
                 (reify IArchiveExtractCallback
                   (getStream [this index extract-ask-mode]
                     (swap! callback-state assoc :index index)
                     (try
                       (let [path (.getProperty archive index PropID/PATH)
                             is-folder (.getProperty archive index PropID/IS_FOLDER)]
                         (when-not is-folder
                           (log/debug "Stream for index" index "path" path)
                           (let [baos (ByteArrayOutputStream.)] ; not with-open
                             (swap! callback-state assoc :stream baos :path path)
                             (reify ISequentialOutStream
                               (write [this data]
                                 (.write baos data 0 (count data))
                                 (count data))))))
                       (catch Throwable e
                         (log/error e "Error getting stream for item" index))))
                   (prepareOperation [this extract-ask-mode]
                     (swap! callback-state assoc :extract-ask-mode extract-ask-mode)
                     (log/trace "preparing" extract-ask-mode))
                   (setOperationResult [this extract-operation-result]
                     (let [cs @callback-state]
                       (when-let [output-stream (:stream cs)]
                         (try
                           (.close output-stream)
                           (let [ba (.toByteArray output-stream)]
                             (swap! extracted-state assoc (:path cs) ba))
                           (catch Exception e
                             (log/error e "Error closing output stream")
                             (throw (SevenZipException. "Error closing output stream"))))))
                     (swap! callback-state assoc :operation-result extract-operation-result)
                     (log/trace "result" extract-operation-result))
                   (setTotal [this total]
                     (swap! callback-state assoc :total total)
                     (log/trace "total" total))
                   (setCompleted [this complete]
                     (swap! callback-state assoc :complete complete)
                     (log/trace "completed" complete))))
       (let [extracted @extracted-state]
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
           (when-let [[path smd-bytes]
                      (->> extracted
                           (filter (comp #(string/ends-with? % ".smd") first))
                           first)]
             (let [smd (spring-script/parse-script (slurp smd-bytes))]
               {:smd (assoc smd ::source path)}))))))))


#_
(time (read-7z-map-fast (map-file "altored_divide_bar_remake_1.5.sd7")))


(defn read-map-data
  ([^java.io.File file]
   (read-map-data file nil))
  ([^java.io.File file opts]
   (let [filename (filename file)]
     (log/info "Loading map" file)
     (try
       (merge
         {:file file}
         (cond
           (string/ends-with? filename ".sdz")
           (read-zip-map file opts)
           (string/ends-with? filename ".sd7")
           (read-7z-map-fast file opts)
           :else
           nil))
       (catch Exception e
         (log/warn e "Error reading map data for" filename))))))

(defn maps []
  (let [before (u/curr-millis)
        m (some->> (map-files)
                   (cp/pmap 2 read-map-data)
                   (filter some?)
                   doall)]
    (log/info "Maps loaded in" (- (u/curr-millis) before) "ms")
    (or m [])))

(defn bots
  ([engine-file]
   (or
     (try
       (when engine-file
         (let [ai-skirmish-dir (io/file engine-file "AI" "Skirmish")
               ai-dirs (some->> (list-files ai-skirmish-dir)
                                seq
                                (filter is-directory))]
           (mapcat
             (fn [^java.io.File ai-dir]
               (->> (list-files ai-dir)
                    (filter is-directory)
                    (map
                      (fn [^java.io.File version-dir]
                        {:bot-name (filename ai-dir)
                         :bot-version (filename version-dir)}))))
             ai-dirs)))
       (catch Exception e
         (log/error e "Error loading bots")))
     [])))


(defn springlobby-map-minimap [map-name]
  (io/file (springlobby-root) "cache" (str map-name ".minimap.png")))

(defn minimap-image-cache-file
  ([map-name]
   (minimap-image-cache-file (app-root) map-name))
  ([root map-name]
   (io/file root "maps-cache" (str map-name ".minimap.png"))))


; https://stackoverflow.com/a/25267111/984393
(defn descendant?
  "Returns true if f is a possible descendant of dir."
  [dir f]
  (string/starts-with?
    (canonical-path f)
    (canonical-path dir)))

(defn child?
  "Returns true if f is a possible descendant of dir."
  [dir f]
  (and dir f
       (= (canonical-path dir)
          (when-let [parent (parent-file f)]
            (canonical-path parent)))))

(defn copy-dir
  [^java.io.File source ^java.io.File dest]
  (if (exists source)
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
         ^"[Ljava.nio.file.CopyOption;" options (into-array ^CopyOption copy-options)
         dest-parent (parent-file dest)]
     (when (and dest-parent (not (exists dest-parent)))
       (.mkdirs dest-parent))
     (Files/copy source-path dest-path options))))

(defn copy
  "Copy a file or directory from source to dest."
  [source dest]
  (if (is-directory source)
    (copy-dir source dest)
    (java-nio-copy source dest)))

(defn copy-missing [source-dir dest-dir]
  (let [source-path (to-path source-dir)]
    (doseq [source (file-seq source-dir)]
      (let [dest (io/file dest-dir (str (.relativize source-path (to-path source))))]
        (cond
          (not (is-file? source)) (log/warn "Not a file" source)
          (exists dest) (log/trace "Skipping copy of" source "since it exists at" dest)
          :else
          (try
            (log/info "Copying" source "to" dest)
            (java-nio-copy source dest {:copy-options [StandardCopyOption/COPY_ATTRIBUTES]})
            (catch Exception e
              (log/warn e "Unable to copy file" source "to" dest
                        {:exists (exists dest)}))))))))

(defn write-image-png [^java.awt.Image image ^File dest]
  (log/debug "Writing image to" dest)
  (ImageIO/write image "png" dest))
