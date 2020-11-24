(ns spring-lobby.fs
  (:require
    [byte-streams]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [com.climate.claypoole :as cp]
    [spring-lobby.fs.smf :as smf]
    [spring-lobby.lua :as lua]
    [spring-lobby.spring :as spring]
    [taoensso.timbre :as log])
  (:import
    (java.awt.image BufferedImage)
    (java.io RandomAccessFile)
    (java.nio ByteBuffer)
    (java.util.zip CRC32 ZipFile)
    (javax.imageio ImageIO)
    (net.sf.sevenzipjbinding ISequentialOutStream SevenZip)
    (net.sf.sevenzipjbinding.impl RandomAccessFileInStream)
    (net.sf.sevenzipjbinding.simple ISimpleInArchiveItem)
    (org.apache.commons.io FilenameUtils)))


(SevenZip/initSevenZipFromPlatformJAR)


(def config-filename "config.edn")


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
  []
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

(defn map-files []
  (->> (io/file (str (spring-root) "/maps"))
       file-seq
       (filter #(.isFile ^java.io.File %))
       (filter #(string/ends-with? (.getName ^java.io.File %) ".sd7"))))

(defn map-files-zip []
  (->> (io/file (str (spring-root) "/maps"))
       file-seq
       (filter #(.isFile ^java.io.File %))
       (filter #(string/ends-with? (.getName ^java.io.File %) ".sdz"))))

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
(let [zip-files (map-files-zip)]
  zip-files)
#_
(open-zip
  (first (map-files-zip)))
#_
(doseq [map-file (map-files-zip)]
  (open-zip map-file))

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

#_
(doseq [map-file (map-files)]
  (open-7z map-file))

#_
(defn extract-7z [from])


(defn engines []
  (log/debug "Loading engine versions")
  (->> (.listFiles (io/file (spring-root) "engine"))
       seq
       (filter #(.isDirectory %))
       (map #(.getName %))))

(defn parse-fieldlist
  [fieldlist]
  (->> fieldlist
       (filter (comp #{:field} first))
       (map
         (fn [[_f field _eq exp]]
           (let [[_exp [field-type value]] exp
                 parsed (case field-type
                          :number (try (Long/parseLong value)
                                       (catch Exception _e
                                         (try (Double/parseDouble value)
                                              (catch Exception _e
                                                nil))))
                          :string (or (second (re-find #"'(.*)'" value))
                                      (second (re-find #"\"(.*)\"" value))
                                      value)
                          nil)] ; TODO
             [(keyword field) parsed])))
       (into {})))

(defn parse-modinfo [modinfo]
  (let [fieldlist (-> modinfo
                      second
                      second
                      last
                      last
                      last
                      (nth 2)
                      rest)]
    (parse-fieldlist fieldlist)))

(defn games []
  (map
    (fn [file]
      (let [modinfo (with-open [zf (new ZipFile file)]
                      (let [entries (enumeration-seq (.entries zf))
                            modinfo-entry
                            (->> entries
                                 (filter (comp #{"modinfo.lua"} string/lower-case #(.getName %)))
                                 first)]
                        (when-let [modinfo (slurp (.getInputStream zf modinfo-entry))]
                          (lua/read-modinfo modinfo))))]
        {:filename (.getName file)
         :modinfo modinfo}))
    (->> (.listFiles (io/file (spring-root) "games"))
         seq
         (filter #(.isFile %)))))

(defn map-names []
  (->> (.listFiles (io/file (spring-root) "maps"))
       seq
       (filter #(.isFile %))
       (map
         (fn [file]
           (let [filename (.getName file)]
             (first (string/split filename #"\.")))))))

(defn spring-config-line [lines field]
  (nth
    (some->> lines
             (filter #(string/includes? % field))
             first
             (re-find #"\s*(.*)=(.*)\s*;"))
    2))

(defn parse-map-data [map-data]
  (spring/parse-script map-data))

(defn before-dot
  [path]
  (-> path
      (string/split #"/")
      last
      (string/split #"\.")
      first))

(defn map-name [path]
  (FilenameUtils/getBaseName path))

(defn parse-mapinfo [file s path]
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

(defn read-zip-map [file]
  (with-open [zf (new ZipFile file)]
    (let [entry-seq (->> (.entries zf)
                         enumeration-seq)]
      (merge
        (when-let [smf-entry
                   (->> entry-seq
                        (filter (comp #(string/ends-with? % ".smf") string/lower-case #(.getName %)))
                        first)]
          (let [smf-path (.getName smf-entry)]
            {:map-name (map-name smf-path)
             ; TODO extract only what's needed
             :smf {::source smf-path
                   :header (smf/decode-map-header (.getInputStream zf smf-entry))}}))
        (when-let [mapinfo-entry
                   (->> entry-seq
                        (filter (comp #{"mapinfo.lua"} string/lower-case #(.getName %)))
                        first)]
          (parse-mapinfo file (slurp (.getInputStream zf mapinfo-entry)) (.getName mapinfo-entry)))
        (when-let [smd-entry
                   (->> entry-seq
                        (filter (comp #(string/ends-with? % ".smd") string/lower-case #(.getName %)))
                        first)]
          (let [smd (when-let [map-data (slurp (.getInputStream zf smd-entry))]
                      (parse-map-data map-data))]
            {:smd (assoc smd ::source (.getName smd-entry))}))))))

#_
(read-zip-map (io/file (spring-root) "maps" "bilateral.sdz"))
#_
(read-zip-map (io/file (spring-root) "maps" "deltasiegedry_revolution_v2.5.sdz"))

(defn slurp-7z-item [item]
  (with-open [baos (java.io.ByteArrayOutputStream.)]
    (when (.extractSlow item
            (reify ISequentialOutStream
              (write [this data]
                (log/trace "got" (count data) "bytes")
                (.write baos data 0 (count data))
                (count data))))
      (byte-streams/convert (.toByteArray baos) String))))

(defn slurp-7z-item-bytes [item]
  (with-open [baos (java.io.ByteArrayOutputStream.)]
    (when (.extractSlow item
            (reify ISequentialOutStream
              (write [this data]
                (log/trace "got" (count data) "bytes")
                (.write baos data 0 (count data))
                (count data))))
      (.toByteArray baos))))

(defn read-7z-map [file]
  (with-open [raf (new RandomAccessFile file "r")
              rafis (new RandomAccessFileInStream raf)
              archive (SevenZip/openInArchive nil rafis)
              simple (.getSimpleInterface archive)]
    (merge
      (when-let [smf-item (->> (.getArchiveItems simple)
                               (filter (comp #(string/ends-with? % ".smf")
                                             string/lower-case
                                             #(.getPath %)))
                               first)]
        (let [smf-path (.getPath smf-item)]
          {:map-name (map-name smf-path)
           ; TODO extract only what's needed
           :smf {::source smf-path
                 :header (smf/decode-map-header (io/input-stream (slurp-7z-item-bytes smf-item)))}}))
      (when-let [mapinfo-item (->> (.getArchiveItems simple)
                                   (filter (comp #{"mapinfo.lua"}
                                                 string/lower-case
                                                 #(.getPath %)))
                                   first)]
        (parse-mapinfo file (slurp-7z-item mapinfo-item) (.getPath mapinfo-item)))
      (when-let [smd-item
                 (->> (.getArchiveItems simple)
                      (filter (comp #(string/ends-with? % ".smd") string/lower-case #(.getPath %)))
                      first)]
        (let [smd (parse-map-data (slurp-7z-item smd-item))]
          {:smd (assoc smd ::source (.getPath smd-item))})))))

#_
(read-7z-map (io/file (spring-root) "maps" "altored_divide_bar_remake_1.3.sd7"))
#_
(read-7z-map (io/file (spring-root) "maps" "beach2_v1.sd7"))


(defn read-map-data [file]
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
  (let [before (System/currentTimeMillis)
        m (some->> (.listFiles (io/file (spring-root) "maps"))
                   seq
                   (filter #(.isFile %))
                   (cp/pmap 2 read-map-data)
                   (filter some?)
                   doall)]
    (log/info "Maps loaded in" (- (System/currentTimeMillis) before) "ms")
    (or m [])))

(defn bots [engine]
  (let [ai-dirs
        (->> (.listFiles (io/file (spring-root) "engine" engine "AI" "Skirmish"))
             seq
             (filter #(.isDirectory %)))]
    (mapcat
      (fn [ai-dir]
        (->> (.listFiles ai-dir)
             (filter #(.isDirectory %))
             (map
               (fn [version-dir]
                 {:bot-name (.getName ai-dir)
                  :bot-version (.getName version-dir)}))))
      ai-dirs)))

#_
(bots "103.0")
#_
(bots "104.0.1-1510-g89bb8e3 maintenance")


(defn map-minimap [map-name]
  (str "/mnt/c/Users/" (System/getProperty "user.name") "/AppData/Roaming/springlobby/cache/" map-name ".minimap.png"))
