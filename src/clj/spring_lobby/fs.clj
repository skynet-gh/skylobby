(ns spring-lobby.fs
  (:require
    [byte-streams]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [com.climate.claypoole :as cp]
    [spring-lobby.fs.smf :as smf]
    [spring-lobby.lua :as lua]
    [spring-lobby.spring.script :as spring-script]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (java.awt.image BufferedImage)
    (java.io FileOutputStream RandomAccessFile)
    (java.nio ByteBuffer)
    (java.util.zip CRC32 ZipEntry ZipFile)
    (javax.imageio ImageIO)
    (net.sf.sevenzipjbinding ISequentialOutStream SevenZip)
    (net.sf.sevenzipjbinding.impl RandomAccessFileInStream)
    (net.sf.sevenzipjbinding.simple ISimpleInArchiveItem)
    (org.apache.commons.io FilenameUtils)))


(set! *warn-on-reflection* true)


(def o (Object.))

(future
  (locking o
    (SevenZip/initSevenZipFromPlatformJAR)))


(def config-filename "config.edn")
(def maps-filename "maps.edn")


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


(defn wsl?
  "Returns true if this system appears to be the Windows Subsystem for Linux."
  []
  (let [{:keys [os-name os-version]} (sys-data)]
    (and
      (string/includes? os-name "Linux")
      (string/includes? os-version "Microsoft")))) ; WSL


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
  (if (string/includes? (os-name) "Linux")
    (into-array
      ^String
      ["LD_LIBRARY_PATH=/var/lib/snapd/lib/gl:/var/lib/snapd/lib/gl32:/var/lib/snapd/void:/snap/springlobby-nsg/416/lib/x86_64-linux-gnu:/snap/springlobby-nsg/416/usr/lib/x86_64-linux-gnu:/snap/springlobby-nsg/416/usr/lib/x86_64-linux-gnu/pulseaudio::/snap/springlobby-nsg/416/lib:/snap/springlobby-nsg/416/usr/lib:/snap/springlobby-nsg/416/lib/x86_64-linux-gnu:/snap/springlobby-nsg/416/usr/lib/x86_64-linux-gnu:/snap/springlobby-nsg/416/usr/lib/x86_64-linux-gnu/dri:/var/lib/snapd/lib/gl:/snap/springlobby-nsg/416/usr/lib/x86_64-linux-gnu/pulseaudio"])
    nil))


(defn executable [common-name]
  (let [{:keys [os-name]} (sys-data)]
    (str
      common-name
      (when (or (string/includes? os-name "Windows")
                (wsl?))
        ".exe"))))

(defn spring-executable []
  (executable "spring"))


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

(defn map-files []
  (->> (io/file (spring-root) "maps")
       file-seq
       (filter #(.isFile ^java.io.File %))
       (filter #(or (string/ends-with? (.getName ^java.io.File %) ".sd7")
                    (string/ends-with? (.getName ^java.io.File %) ".sdz")))))

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

(defn extract-7z [^java.io.File f]
  (let [fname (.getName f)
        dir (if (string/includes? fname ".")
              (subs fname 0 (.lastIndexOf fname "."))
              fname)]
    (with-open [raf (new RandomAccessFile f "r")
                rafis (new RandomAccessFileInStream raf)
                archive (SevenZip/openInArchive nil rafis)
                simple (.getSimpleInterface archive)]
      (log/trace archive "has" (.getNumberOfItems archive) "items")
      (doseq [^ISimpleInArchiveItem item (.getArchiveItems simple)]
        (let [path (.getPath item)
              to (io/file (.getParentFile f) dir path)]
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
    (log/info "Finished extracting" f "to" dir)))


#_
(extract-7z (io/file (spring-root) "engine" "spring_103.0_win32-minimal-portable.7z"))


(defn sync-version-to-engine-version
  "Returns the Spring engine version from a sync version. For some reason, engine '103.0' has sync
  version '103' whereas for engine 104.0.1-1553-gd3c0012 maintenance the sync version is the same,
  '104.0.1-1553-gd3c0012 maintenance'."
  [sync-version]
  (if (= sync-version (string/replace sync-version #"[^\d]" ""))
    (str sync-version ".0")
    sync-version))


(defn sync-version [engine-dir]
  (let [engine-exe (io/file engine-dir (spring-executable))
        command [(.getAbsolutePath engine-exe) "--sync-version"]
        ^"[Ljava.lang.String;" cmdarray (into-array String command)
        runtime (Runtime/getRuntime)
        process (.exec runtime cmdarray)]
    (.waitFor process 1000 java.util.concurrent.TimeUnit/MILLISECONDS)
    (let [sync-version (string/trim (slurp (.getInputStream process)))]
      (log/info "Discovered sync-version of" engine-exe "is" (str "'" sync-version "'"))
      sync-version)))


(defn engine-dirs []
  (->> (.listFiles (io/file (spring-root) "engine"))
       seq
       (filter #(.isDirectory ^java.io.File %))))

(defn engine-data [^java.io.File engine-dir]
  (let [sync-version (sync-version engine-dir)]
    {:engine-dir-absolute-path (.getAbsolutePath engine-dir)
     :engine-dir-filename (.getName engine-dir)
     :sync-version sync-version
     :engine-version (sync-version-to-engine-version sync-version)}))


(defn slurp-zip-entry [^ZipFile zip-file entries entry-filename-lowercase]
  (when-let [entry
             (->> entries
                  (filter (comp #{entry-filename-lowercase} string/lower-case #(.getName ^ZipEntry %)))
                  first)]
    (slurp (.getInputStream zip-file entry))))


(defn mod-files []
  (->> (.listFiles (io/file (spring-root) "games"))
       seq
       (filter #(.isFile ^java.io.File %))))

(defn read-mod-file [^java.io.File file]
  (with-open [zf (new ZipFile file)]
    (let [entries (enumeration-seq (.entries zf))
          try-entry-lua (fn [filename]
                          (try
                            (when-let [slurped (slurp-zip-entry zf entries filename)]
                              (lua/read-modinfo slurped))
                            (catch Exception e
                              (log/warn e "Error loading" filename "from" file))))]
      {:filename (.getName file)
       :absolute-path (.getAbsolutePath file)
       :modinfo (try-entry-lua "modinfo.lua")
       :modoptions (try-entry-lua "modoptions.lua")
       :engineoptions (try-entry-lua "engineoptions.lua")
       :luaai (try-entry-lua "luaai.lua")
       ::source :archive})))

(defn mods []
  (->> (mod-files)
       (map read-mod-file)
       doall))

(defn map-names []
  (->> (.listFiles (io/file (spring-root) "maps"))
       seq
       (filter #(.isFile ^java.io.File %))
       (map
         (fn [^java.io.File file]
           (let [filename (.getName file)]
             (first (string/split filename #"\.")))))))

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

(defn read-zip-map [^java.io.File file]
  (with-open [zf (new ZipFile file)]
    (let [entry-seq (->> (.entries zf)
                         enumeration-seq)]
      (merge
        (when-let [^ZipEntry smf-entry
                   (->> entry-seq
                        (filter (comp #(string/ends-with? % ".smf") string/lower-case #(.getName ^java.io.File %)))
                        first)]
          (let [smf-path (.getName smf-entry)]
            {:map-name (map-name smf-path)
             ; TODO extract only what's needed
             :smf {::source smf-path
                   :header (smf/decode-map-header (.getInputStream zf smf-entry))}}))
        (when-let [^ZipEntry mapinfo-entry
                   (->> entry-seq
                        (filter (comp #{"mapinfo.lua"} string/lower-case #(.getName ^java.io.File %)))
                        first)]
          (parse-mapinfo file (slurp (.getInputStream zf mapinfo-entry)) (.getName mapinfo-entry)))
        (when-let [^ZipEntry smd-entry
                   (->> entry-seq
                        (filter (comp #(string/ends-with? % ".smd") string/lower-case #(.getName ^ZipEntry %)))
                        first)]
          (let [smd (when-let [map-data (slurp (.getInputStream zf smd-entry))]
                      (spring-script/parse-script map-data))]
            {:smd (assoc smd ::source (.getName smd-entry))}))))))


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

(defn read-7z-map [^java.io.File file]
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
        (let [smf-path (.getPath smf-item)]
          {:map-name (map-name smf-path)
           ; TODO extract only what's needed
           :smf {::source smf-path
                 :header (smf/decode-map-header (io/input-stream (slurp-7z-item-bytes smf-item)))}}))
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
          {:smd (assoc smd ::source (.getPath smd-item))})))))


(defn read-map-data [^java.io.File file]
  (let [filename (.getName file)]
    (log/info "Loading map" filename)
    (try
      (merge
        {:filename filename}
        (cond
          (string/ends-with? filename ".sdz")
          (read-zip-map file)
          (string/ends-with? filename ".sd7")
          (read-7z-map file)
          :else
          nil))
      (catch Exception e
        (log/warn e "Error reading map data for" filename)))))

(defn maps []
  (let [before (u/curr-millis)
        m (some->> (map-files)
                   (cp/pmap 2 read-map-data)
                   (filter some?)
                   doall)]
    (log/info "Maps loaded in" (- (u/curr-millis) before) "ms")
    (or m [])))

(defn bots [engine]
  (let [ai-skirmish-dir (io/file (spring-root) "engine" engine "AI" "Skirmish")
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


(defn map-minimap [map-name]
  (io/file (springlobby-root) "cache" (str map-name ".minimap.png")))
