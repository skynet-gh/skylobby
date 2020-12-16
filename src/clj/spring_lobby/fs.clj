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
    (java.io FileOutputStream RandomAccessFile)
    (java.nio ByteBuffer)
    (java.util.zip CRC32 ZipEntry ZipFile)
    (net.sf.sevenzipjbinding IArchiveExtractCallback ISequentialOutStream PropID SevenZip SevenZipException)
    (net.sf.sevenzipjbinding.impl RandomAccessFileInStream)
    (net.sf.sevenzipjbinding.simple ISimpleInArchiveItem)
    (org.apache.commons.io FilenameUtils FileUtils)))


(set! *warn-on-reflection* true)


(defn init-7z! []
  (SevenZip/initSevenZipFromPlatformJAR))


(def config-filename "config.edn")
(def maps-filename "maps.edn")


(defn canonical-path [^java.io.File f]
  (when f
    (.getCanonicalPath f)))

; TODO always use canonical-path
(defn absolute-path [^java.io.File f]
  (when f
    (.getAbsolutePath f)))

(defn filename [^java.io.File f]
  (when f
    (.getName f)))

(defn exists [^java.io.File f]
  (when f
    (.exists f)))


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
  []
  (let [{:keys [os-name os-version]} (sys-data)]
    (and
      (string/includes? os-name "Linux")
      (string/includes? os-version "Microsoft")))) ; WSL

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
  [^java.io.File f]
  (if (wsl?)
    (let [path (.getAbsolutePath f)
          command ["wslpath" "-w" path]
          ^"[Ljava.lang.String;" cmdarray (into-array String command)
          runtime (Runtime/getRuntime)
          process (.exec runtime cmdarray)]
      (.waitFor process 1000 java.util.concurrent.TimeUnit/MILLISECONDS)
      (let [windows-path (string/trim (slurp (.getInputStream process)))]
        (log/info "Converted path" path "to" windows-path)
        windows-path))
    (.getAbsolutePath f)))


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
  ^java.io.File []
  (let [{:keys [os-name os-version user-name user-home] :as sys-data} (sys-data)]
    (cond
      (string/includes? os-name "Linux")
      (if (string/includes? os-version "Microsoft") ; WSL
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
  ^java.io.File []
  (let [{:keys [os-name os-version user-name user-home] :as sys-data} (sys-data)]
    (cond
      (string/includes? os-name "Linux")
      (if (string/includes? os-version "Microsoft") ; WSL
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
  (let [{:keys [os-name os-version user-name user-home] :as sys-data} (sys-data)]
    (cond
      (string/includes? os-name "Linux")
      (if (string/includes? os-version "Microsoft") ; WSL
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
  (let [{:keys [os-name os-version user-name user-home] :as sys-data} (sys-data)]
    (cond
      (string/includes? os-name "Linux")
      (if (string/includes? os-version "Microsoft") ; WSL
        (io/file "/mnt" "c" "Users" user-name ".alt-spring-lobby")
        (io/file user-home ".alt-spring-lobby"))
      (string/includes? os-name "Windows")
      (io/file user-home ".alt-spring-lobby")
      :else
      (throw (ex-info "Unable to determine app root for this system"
                      {:sys-data sys-data})))))

(defn map-files-7z []
  (->> (io/file (spring-root) "maps")
       file-seq
       (filter #(.isFile ^java.io.File %))
       (filter #(string/ends-with? (.getName ^java.io.File %) ".sd7"))))

(defn map-files-zip []
  (->> (io/file (spring-root) "maps")
       file-seq
       (filter #(.isFile ^java.io.File %))
       (filter #(string/ends-with? (.getName ^java.io.File %) ".sdz"))))

(defn download-dir ^java.io.File
  []
  (io/file (app-root) "download"))

(defn isolation-dir
  "Returns the isolation dir for spring in this app, usually $HOME/.alt-spring-lobby/spring"
  ^java.io.File
  []
  (io/file (app-root) "spring"))

(defn map-files
  ([]
   (map-files (isolation-dir)))
  ([root]
   (->> (io/file root "maps")
        file-seq
        (filter #(.isFile ^java.io.File %))
        (filter #(or (string/ends-with? (.getName ^java.io.File %) ".sd7")
                     (string/ends-with? (.getName ^java.io.File %) ".sdz"))))))

#_
(defn open-zip [^java.io.File from]
  (let [zf (new ZipFile from)
        entries (enumeration-seq (.entries zf))]
    (doseq [^java.util.zip.ZipEntry entry entries]
      (println (.getName entry) (.getCrc entry))
      (let [entry-name (.getName entry)
            crc-long (.getCrc entry)
            dir (.isDirectory entry)]
        (when (re-find #"(?i)mini" entry-name)
          (println (.getName from) entry-name))))))

; https://github.com/spring/spring/blob/master/rts/System/FileSystem/ArchiveScanner.cpp#L782-L858
(defn spring-crc [named-crcs]
  (let [^CRC32 res (CRC32.)
        sorted (sort-by :crc-name named-crcs)]
    (doseq [{:keys [^String crc-name crc-long]} sorted]
      (.update res (.getBytes crc-name))
      (.update res (.array (.putLong (ByteBuffer/allocate 4) crc-long)))) ; TODO fix array overflow
    (.getValue res))) ; TODO 4711 if 0


#_
(defn open-7z [^java.io.File from]
  (with-open [raf (new RandomAccessFile from "r")
              rafis (new RandomAccessFileInStream raf)
              archive (SevenZip/openInArchive nil rafis)
              simple (.getSimpleInterface archive)]
    (log/trace from "has" (.getNumberOfItems archive) "items")
    (doseq [^ISimpleInArchiveItem item (.getArchiveItems simple)]
      (let [path (.getPath item)
            crc (.getCRC item)
            crc-long (Integer/toUnsignedString crc)
            dir (.isFolder item)
            from-path (.getPath (io/file from))
            to (str (subs from-path 0 (.lastIndexOf from-path ".")) ".png")]
        (when (string/includes? (string/lower-case path) "mini")
          (log/info path))
        (when (re-find #"(?i)mini\.png" path)
          (log/info "Extracting" path "to" to)
          (with-open [baos (java.io.ByteArrayOutputStream.)]
            (let [res (.extractSlow item
                        (reify ISequentialOutStream
                          (write [this data]
                            (log/trace "got" (count data) "bytes")
                            (.write baos data 0 (count data))
                            (count data))))
                  ^BufferedImage image (with-open [is (io/input-stream (.toByteArray baos))]
                                         (ImageIO/read is))]
              (log/info "Extract result" res)
              (log/info "Wrote image" (ImageIO/write image "png" (io/file to))))))))))

(defn- extract-7z
  ([^java.io.File f]
   (let [fname (.getName f)
         dir (if (string/includes? fname ".")
               (subs fname 0 (.lastIndexOf fname "."))
               fname)
         dest (io/file (.getParentFile f) dir)]
     (extract-7z f dest)))
  ([^java.io.File f ^java.io.File dest]
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
  ([^java.io.File f]
   (let [fname (.getName f)
         dir (if (string/includes? fname ".")
               (subs fname 0 (.lastIndexOf fname "."))
               fname)
         dest (io/file (.getParentFile f) dir)]
     (extract-7z-fast f dest)))
  ([^java.io.File f ^java.io.File dest]
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
  (if (= sync-version (string/replace sync-version #"[^\d]" ""))
    (str sync-version ".0")
    sync-version))


(defn sync-version [engine-dir]
  (let [engine-exe (io/file engine-dir (spring-headless-executable))
        _ (.setExecutable engine-exe true)
        command [(.getAbsolutePath engine-exe) "--sync-version"]
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
   (->> (.listFiles (io/file root "engine"))
        seq
        (filter #(.isDirectory ^java.io.File %)))))

(defn engine-data [^java.io.File engine-dir]
  (let [sync-version (sync-version engine-dir)]
    {:absolute-path (.getAbsolutePath engine-dir)
     :engine-dir-filename (.getName engine-dir)
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
   (seq (.listFiles (io/file root "games")))))

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
         {:filename (.getName file)
          :absolute-path (.getAbsolutePath file)
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
       {:filename (.getName file)
        :absolute-path (.getAbsolutePath file)
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
     (.isDirectory file)
     (read-mod-directory file opts)
     (string/ends-with? (.getName file) ".sdz")
     (read-mod-zip-file file opts)
     :else
     (log/warn "Unknown mod file type" file))))

(defn map-names []
  (->> (.listFiles (io/file (spring-root) "maps"))
       seq
       (filter #(.isFile ^java.io.File %))
       (map
         (fn [^java.io.File file]
           (let [filename (.getName file)]
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
      (log/error e "Failed to parse mapinfo.lua from" (.getName file)))))

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
                   {:minimap-bytes minimap
                    :minimap-image (smf/decompress-minimap minimap)})
                 (when-let [metalmap (:metalmap body)]
                   {:metalmap-bytes metalmap
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
  (with-open [baos (java.io.ByteArrayOutputStream.)]
    (when (.extractSlow item
            (reify ISequentialOutStream
              (write [this data]
                (log/trace "got" (count data) "bytes")
                (.write baos data 0 (count data))
                (count data))))
      (byte-streams/convert (.toByteArray baos) String))))

(defn slurp-7z-item-bytes [^ISimpleInArchiveItem item]
  (with-open [baos (java.io.ByteArrayOutputStream.)]
    (when (.extractSlow item
            (reify ISequentialOutStream
              (write [this data]
                (log/trace "got" (count data) "bytes")
                (.write baos data 0 (count data))
                (count data))))
      (.toByteArray baos))))

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

(defn read-7z-map
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


(defn read-map-data
  ([^java.io.File file]
   (read-map-data file nil))
  ([^java.io.File file opts]
   (let [filename (.getName file)]
     (log/info "Loading map" filename)
     (try
       (merge
         {:filename filename
          :absolute-path (absolute-path file)}
         (cond
           (string/ends-with? filename ".sdz")
           (read-zip-map file opts)
           (string/ends-with? filename ".sd7")
           (read-7z-map file opts)
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
  ([engine-absolute-path]
   (or
     (try
       (when engine-absolute-path
         (let [ai-skirmish-dir (io/file engine-absolute-path "AI" "Skirmish")
               ai-dirs (some->> (.listFiles ai-skirmish-dir)
                                seq
                                (filter #(.isDirectory ^java.io.File %)))]
           (mapcat
             (fn [^java.io.File ai-dir]
               (->> (.listFiles ai-dir)
                    (filter #(.isDirectory ^java.io.File %))
                    (map
                      (fn [^java.io.File version-dir]
                        {:bot-name (.getName ai-dir)
                         :bot-version (.getName version-dir)}))))
             ai-dirs)))
       (catch Exception e
         (log/error e "Error loading bots")))
     [])))


(defn map-minimap [map-name]
  (io/file (springlobby-root) "cache" (str map-name ".minimap.png")))


; https://stackoverflow.com/a/25267111/984393
(defn descendant?
  "Returns true if f is a possible descendant of dir."
  [dir f]
  (string/starts-with?
    (.getCanonicalPath f)
    (.getCanonicalPath dir)))

(defn child?
  "Returns true if f is a possible descendant of dir."
  [dir f]
  (and dir f
       (= (.getCanonicalPath dir)
          (when-let [parent (.getParentFile f)]
            (.getCanonicalPath parent)))))
