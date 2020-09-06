(ns spring-lobby
  (:require
    [cljfx.ext.table-view :as fx.ext.table-view]
    [clojure.core.async :as async]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [spring-lobby.client :as client]
    [spring-lobby.spring :as spring]
    [taoensso.timbre :refer [debug info trace warn]])
  (:import
    (java.io RandomAccessFile)
    (java.nio ByteBuffer)
    (java.util.zip CRC32 ZipFile)
    (javax.imageio ImageIO)
    (net.sf.sevenzipjbinding ISequentialOutStream SevenZip)
    (net.sf.sevenzipjbinding.impl RandomAccessFileInStream)))


(set! *warn-on-reflection* true)


(def default-state
  {:username "skynet9001"
   :password "1234dogs"})

(defonce *state
  (atom default-state))

#_
(pprint @*state)

(defmulti event-handler :event/type)


(defn battle-opts []
  {:mod-hash -1706632985
   :engine-version "103.0"
   ;:engine-version "104.0.1-1510-g89bb8e3 maintenance"
   :map-name "Dworld Acidic"
   :title "deth"
   :mod-name "Balanced Annihilation V9.79.4"
   :map-hash -1611391257})


(SevenZip/initSevenZipFromPlatformJAR)

(defn spring-root
  "Returns the root directory for Spring"
  []
  (if (string/starts-with? (System/getProperty "os.name") "Windows")
    (io/file (System/getProperty "user.home") "Documents" "My Games" "Spring")
    (do
      (io/file (System/getProperty "user.home") "spring") ; TODO make sure
      (str "/mnt/c/Users/" (System/getProperty "user.name") "/Documents/My Games/Spring")))) ; TODO remove

(defn map-files []
  (->> (io/file (str (spring-root) "/maps"))
       file-seq
       (filter #(.isFile %))
       (filter #(string/ends-with? (.getName %) ".sd7"))))

(defn map-files-zip []
  (->> (io/file (str (spring-root) "/maps"))
       file-seq
       (filter #(.isFile %))
       (filter #(string/ends-with? (.getName %) ".sdz"))))


(defn open-zip [from]
  (let [zf (ZipFile. from)
        entries (enumeration-seq (.entries zf))]
    (doseq [entry entries]
      (println (.getName entry) (.getCrc entry))
      (let [entry-name (.getName entry)
            crc (.getCrc entry)
            crc-long (.getValue crc)
            dir (.isDirectory entry)]
        (when (re-find #"(?i)mini" entry-name)
          (println (.getName from) entry-name))))))

; https://github.com/spring/spring/blob/master/rts/System/FileSystem/ArchiveScanner.cpp#L782-L858
(defn spring-crc [named-crcs]
  (let [res (CRC32.)
        sorted (sort-by :crc-name named-crcs)]
    (doseq [{:keys [crc-name crc-long]} sorted]
      (.update res (.getBytes crc-name))
      (.update res (.array (.putLong (ByteBuffer/allocate 4))))) ; TODO fix array overflow
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


(defn open-7z [from]
  (with-open [raf (RandomAccessFile. from "r")
              rafis (RandomAccessFileInStream. raf)
              archive (SevenZip/openInArchive nil rafis)]
    (trace from "has" (.getNumberOfItems archive) "items")
    (doseq [item (.getArchiveItems (.getSimpleInterface archive))]
      (let [path (.getPath item)
            crc (.getCRC item)
            crc-long (Integer/toUnsignedString crc)
            dir (.isFolder item)
            from-path (.getPath (io/file from))
            to (str (subs from-path 0 (.lastIndexOf from-path ".")) ".png")]
        (when (string/includes? (string/lower-case path) "mini")
          (info path))
        (when (re-find #"(?i)mini\.png" path)
          (info "Extracting" path "to" to)
          (with-open [baos (java.io.ByteArrayOutputStream.)]
            (let [res (.extractSlow item
                        (reify ISequentialOutStream
                          (write [this data]
                            (trace "got" (count data) "bytes")
                            (.write baos data 0 (count data))
                            (count data))))
                  image (with-open [is (io/input-stream (.toByteArray baos))]
                          (ImageIO/read is))]
              (info "Extract result" res)
              (info "Wrote image" (ImageIO/write image "png" (io/file to))))))))))


#_
(doseq [map-file (map-files)]
  (open-7z map-file))

#_
(defn extract-7z [from])


(defn menu-view [_opts]
  {:fx/type :menu-bar
   :menus
   [{:fx/type :menu
     :text "Server"
     :items [{:fx/type :menu-item
              :text "Connect"}
             {:fx/type :menu-item
              :text "Disconnect"}
             {:fx/type :menu-item
              :text "Pick Server"}]}
    {:fx/type :menu
     :text "Edit"
     :items [{:fx/type :menu-item
              :text "menu2 item1"}
             {:fx/type :menu-item
              :text "menu2 item2"}]}
    {:fx/type :menu
     :text "Tools"
     :items [{:fx/type :menu-item
              :text "menu3 item1"}
             {:fx/type :menu-item
              :text "menu3 item2"}]}
    {:fx/type :menu
     :text "Help"
     :items [{:fx/type :menu-item
              :text "menu4 item1"}
             {:fx/type :menu-item
              :text "menu4 item2"}]}]})

(defmethod event-handler ::select-battle [e]
  (swap! *state assoc :selected-battle (-> e :fx/event :battle-id)))

(defn battles-table [{:keys [battles users]}]
  {:fx/type fx.ext.table-view/with-selection-props
   :props {:selection-mode :single
           :on-selected-item-changed {:event/type ::select-battle}}
   :desc
   {:fx/type :table-view
    :items (vec (vals battles))
    :columns
    [{:fx/type :table-column
      :text "Status"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (:battle-type i))})}}
     {:fx/type :table-column
      :text "Country"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (:country (get users (:host-username i))))})}}
     {:fx/type :table-column
      :text " "
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (:battle-rank i))})}}
     {:fx/type :table-column
      :text "Players"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (count (:users i)))})}}
     {:fx/type :table-column
      :text "Max"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (:battle-maxplayers i))})}}
     {:fx/type :table-column
      :text "Spectators"}
     {:fx/type :table-column
      :text "Running"}
     {:fx/type :table-column
      :text "Battle Name"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (:battle-title i))})}}
     {:fx/type :table-column
      :text "Game"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (:battle-modname i))})}}
     {:fx/type :table-column
      :text "Map"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (:battle-map i))})}}
     {:fx/type :table-column
      :text "Host"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (:host-username i))})}}
     {:fx/type :table-column
      :text "Engine"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (:battle-engine i) " " (:battle-version i))})}}]}})

(defn user-table [{:keys [users]}]
  {:fx/type :table-view
   :items (vec (vals users))
   :columns
   [{:fx/type :table-column
     :text "Status"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe (fn [i] {:text (str (:client-status i))})}}
    {:fx/type :table-column
     :text "Country"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe (fn [i] {:text (str (:country i))})}}
    {:fx/type :table-column
     :text "Rank"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe (fn [_i] {:text ""})}}
    {:fx/type :table-column
     :text "Username"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe (fn [i] {:text (str (:username i))})}}
    {:fx/type :table-column
     :text "Lobby Client"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe (fn [i] {:text (str (:user-agent i))})}}]})

(defn update-disconnected []
  (swap! *state dissoc :battle :battles :chanenls :client :client-deferred :my-channels :users))

(defmethod event-handler ::disconnect [_e]
  (client/disconnect (:client @*state))
  (update-disconnected))

(defmethod event-handler ::connect [_e]
  (let [client-deferred (client/client)] ; TODO host port
    (swap! *state assoc :client-deferred client-deferred)
    (async/thread
      (let [client @client-deferred]
        (client/connect *state client) ; TODO username password
        (swap! *state assoc :client client)
        (loop []
          (if (and client (not (.isClosed client)))
            (do
              (debug "Client is still connected")
              (async/<!! (async/timeout 20000))
              (recur))
            (do
              (info "Client was disconnected")
              (update-disconnected))))))))


(defn client-buttons [{:keys [client client-deferred]}]
  {:fx/type :h-box
   :alignment :top-left
   :children
   (concat
     [{:fx/type :button
       :text (if client
               "Disconnect"
               (if client-deferred
                 "Connecting..."
                 "Connect"))
       :disable (boolean (and (not client) client-deferred))
       :on-action {:event/type (if client ::disconnect ::connect)}}])})

(defmethod event-handler ::host-battle [_e]
  (client/open-battle (:client @*state) (battle-opts)))

(defmethod event-handler ::leave-battle [_e]
  (client/send-message (:client @*state) "LEAVEBATTLE"))

(defmethod event-handler ::join-battle [_e]
  (when-let [selected (-> *state deref :selected-battle)]
    (client/send-message (:client @*state) (str "JOINBATTLE " selected))))

(defn battles-buttons [{:keys [battle client selected-battle]}]
  {:fx/type :h-box
   :alignment :top-left
   :children
   (concat
     (if battle
       [{:fx/type :button
         :text "Leave Battle"
         :on-action {:event/type ::leave-battle}}]
       (when client
         (concat
           [{:fx/type :button
             :text "Host Battle"
             :on-action {:event/type ::host-battle}}]
           (when selected-battle
             [{:fx/type :button
               :text "Join Battle"
               :on-action {:event/type ::join-battle}}])))))})

; doesn't work from WSL
(defn spring-env []
  (into-array String ["SPRING_WRITEDIR=C:\\Users\\craig\\.alt-spring-lobby\\spring\\write"
                      "SPRING_DATADIR=C:\\Users\\craig\\.alt-spring-lobby\\spring\\data"]))

(defmethod event-handler ::add-bot [_e]
  (let [bot-num 1
        bot-name "KAIK"
        bot-version "0.13"
        bot-status 0
        bot-color 0
        message (str "ADDBOT kekbot" bot-num " " bot-status " " bot-color " " bot-name "|" bot-version)]
    (client/send-message (:client @*state) message)))

(defmethod event-handler ::start-battle [_e]
  (let [{:keys [battle battles users username]} @*state
        battle (update battle :users #(into {} (map (fn [[k v]] [k (assoc v :username k :user (get users k))]) %)))
        battle (merge (get battles (:battle-id battle)) battle)
        {:keys [battle-version]} battle
        script (spring/script-data battle {:myplayername username})
        script-txt (spring/script-txt script)
        engine-file (io/file (spring-root) "engine" battle-version "spring.exe")
        ;script-file (io/file (spring-root) "script.txt")
        ;homedir (io/file (System/getProperty "user.home"))
        ;script-file (io/file homedir "script.txt")
        script-file (io/file "/mnt/c/Users/craig/.alt-spring-lobby/spring/script.txt") ; TODO remove
        isolation-dir (io/file "/mnt/c/Users/craig/.alt-spring-lobby/spring/engine" battle-version)] ; TODO remove
        ;homedir (io/file "C:\\Users\\craig\\.alt-spring-lobby\\spring") ; TODO remove
    (try
      (spit script-file script-txt)
      (let [command [(.getAbsolutePath engine-file)
                     "--isolation-dir" (str "C:\\Users\\craig\\.alt-spring-lobby\\spring\\engine\\" battle-version) ; TODO windows path
                     "C:\\Users\\craig\\.alt-spring-lobby\\spring\\script.txt"] ; TODO windows path
            runtime (Runtime/getRuntime)]
        (info "Running '" command "'")
        (let [process (.exec runtime (into-array String command) nil isolation-dir)]
          (async/thread
            (with-open [reader (io/reader (.getInputStream process))]
              (loop []
                (if-let [line (.readLine reader)]
                  (do
                    (info "(spring out)" line)
                    (recur))
                  (info "Spring stdout stream closed")))))
          (async/thread
            (with-open [reader (io/reader (.getErrorStream process))]
              (loop []
                (if-let [line (.readLine reader)]
                  (do
                    (info "(spring err)" line)
                    (recur))
                  (info "Spring stderr stream closed")))))))
      (catch Exception e
        (warn e)))
    (client/send-message (:client @*state) "MYSTATUS 1")))


(defn battle-table [{:keys [battle users]}]
  (let [items (concat
                (mapv 
                  (fn [[k v]] (assoc v :username k :user (get users k)))
                  (:users battle))
                (mapv 
                  (fn [[k v]] (assoc v :username (str k "(" (:owner v) ")")))
                  (:bots battle)))]
    {:fx/type :v-box
     :alignment :top-left
     :children
     (concat
       [{:fx/type :table-view
         :items items
         :columns
         [{:fx/type :table-column
           :text "Country"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [i] {:text (str (:country (:user i)))})}}
          {:fx/type :table-column
           :text "Status"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [i] {:text (str (select-keys (:client-status (:user i)) [:bot :access]))})}}
          {:fx/type :table-column
           :text "Ingame"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [i] {:text (str (:ingame (:client-status (:user i))))})}}
          {:fx/type :table-column
           :text "Faction"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [i] {:text (str (:side (:battle-status i)))})}}
          {:fx/type :table-column
           :text "Rank"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [i] {:text (str (:rank (:client-status (:user i))))})}}
          {:fx/type :table-column
           :text "TrueSkill"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [_i] {:text ""})}}
          {:fx/type :table-column
           :text "Color"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [i] {:text (str (:team-color i))})}}
          {:fx/type :table-column
           :text "Nickname"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [i] {:text (str (:username i))})}}
          {:fx/type :table-column
           :text "Player ID"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [i] {:text (str (:id (:battle-status i)))})}}
          {:fx/type :table-column
           :text "Team ID"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [i] {:text (str (:ally (:battle-status i)))})}}
          {:fx/type :table-column
           :text "Bonus"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [i] {:text (str (:handicap (:battle-status i)) "%")})}}]}]
       (when battle
         [{:fx/type :h-box
           :alignment :top-left
           :children
           [{:fx/type :button
             :text "Start Battle"
             :on-action {:event/type ::start-battle}}
            {:fx/type :button
             :text "Add Bot"
             :on-action {:event/type ::add-bot}}]}]))}))


(defn root-view [{{:keys [client client-deferred users battles battle selected-battle]} :state}]
  {:fx/type :stage
   :showing true
   :title "Alt Spring Lobby"
   :width 900
   :height 700
   :scene {:fx/type :scene
           :root {:fx/type :v-box
                  :alignment :top-left
                  :children [{:fx/type menu-view}
                             {:fx/type client-buttons
                              :client client
                              :client-deferred client-deferred}
                             {:fx/type user-table
                              :users users}
                             {:fx/type battles-table
                              :battles battles
                              :users users}
                             {:fx/type battles-buttons
                              :battle battle
                              :client client
                              :selected-battle selected-battle}
                             {:fx/type battle-table
                              :battles battles
                              :battle battle
                              :users users}]}}})
