(ns spring-lobby
  (:require
    [cljfx.api :as fx]
    [cljfx.ext.node :as fx.ext.node]
    [cljfx.ext.table-view :as fx.ext.table-view]
    [clojure.core.async :as async]
    [clojure.java.io :as io]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as string]
    [com.evocomputing.colors :as colors]
    [spring-lobby.client :as client]
    [spring-lobby.spring :as spring]
    [taoensso.timbre :refer [debug info trace warn] :as log])
  (:import
    (java.awt.image BufferedImage)
    (java.io RandomAccessFile)
    (java.nio ByteBuffer)
    (java.util.zip CRC32 ZipFile)
    (javax.imageio ImageIO)
    (manifold.stream SplicedStream)
    (net.sf.sevenzipjbinding ISequentialOutStream SevenZip)
    (net.sf.sevenzipjbinding.impl RandomAccessFileInStream)
    (net.sf.sevenzipjbinding.simple ISimpleInArchiveItem)))


(set! *warn-on-reflection* true)


(def default-state
  {:username "skynet9001"
   :password "1234dogs"
   :map-name "Dworld Acidic"
   :mod-name "Balanced Annihilation V9.79.4"
   :title "deth"
   :engine-version "103.0"})

(defonce *state
  (atom default-state))


(defmulti event-handler :event/type)


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
       (filter #(.isFile ^java.io.File %))
       (filter #(string/ends-with? (.getName ^java.io.File %) ".sd7"))))

(defn map-files-zip []
  (->> (io/file (str (spring-root) "/maps"))
       file-seq
       (filter #(.isFile ^java.io.File %))
       (filter #(string/ends-with? (.getName ^java.io.File %) ".sdz"))))


(defn open-zip [^java.io.File from]
  (let [zf (ZipFile. from)
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
  (with-open [raf (RandomAccessFile. from "r")
              rafis (RandomAccessFileInStream. raf)
              archive (SevenZip/openInArchive nil rafis)
              simple (.getSimpleInterface archive)]
    (trace from "has" (.getNumberOfItems archive) "items")
    (doseq [^ISimpleInArchiveItem item (.getArchiveItems simple)]
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
                  ^BufferedImage image (with-open [is (io/input-stream (.toByteArray baos))]
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
       :describe (fn [i] {:text (str (select-keys i [:battle-type :battle-passworded]))})}}
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
  (swap! *state dissoc :battle :battles :chanenls :client :client-deferred :my-channels :users)
  nil)

(defmethod event-handler ::print-state [_e]
  (pprint *state))

(defmethod event-handler ::disconnect [_e]
  (when-let [client (:client @*state)]
    (client/disconnect client))
  (update-disconnected))

(defn connected-loop [state-atom client-deferred]
  (swap! state-atom assoc
         :connected-loop
         (future
           (try
             (let [^SplicedStream client @client-deferred]
               (when-not ())
               (client/connect state-atom client) ; TODO username password
               (swap! state-atom assoc :client client :login-error nil)
               (loop []
                 (if (and client (not (.isClosed client)))
                   (do
                     (debug "Client is still connected")
                     (async/<!! (async/timeout 20000))
                     (recur))
                   (do
                     (info "Client was disconnected")
                     (update-disconnected)))))
             (catch Exception e
               (log/error e "Unable to connect")
               (swap! state-atom assoc :login-error (str (.getMessage e)))
               (update-disconnected)))
           nil)))

(defmethod event-handler ::connect [_e]
  (let [client-deferred (client/client)] ; TODO host port
    (swap! *state assoc :client-deferred client-deferred)
    (connected-loop *state client-deferred)))


(defn client-buttons [{:keys [client client-deferred username password login-error]}]
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
       :on-action {:event/type (if client ::disconnect ::connect)}}
      {:fx/type :text-field
       :text username
       :disable (boolean (or client client-deferred))
       :on-action {:event/type ::username-change}}
      {:fx/type :password-field
       :text password
       :disable (boolean (or client client-deferred))
       :on-action {:event/type ::password-change}}]
     (when login-error
       [{:fx/type :v-box
         :alignment :center
         :children
         [{:fx/type :label
           :text (str login-error)
           :style {:-fx-text-fill "#FF0000"
                   :-fx-max-width 360}}]}])
     [{:fx/type :h-box
       :alignment :top-right
       :children
       [{:fx/type :button
         :text "Print state"
         :on-action {:event/type ::print-state}
         :alignment :top-right}]}])})

(defmethod event-handler ::username-change [e]
  (swap! *state assoc :username (:fx/event e)))

(defmethod event-handler ::password-change [e]
  (swap! *state assoc :password (:fx/event e)))


(defn host-battle []
  (let [{:keys [client] :as state} @*state]
    (client/open-battle client
      (assoc
        (select-keys state [:battle-password :title :engine-version :mod-name :map-name])
        :mod-hash -1
        :map-hash -1))))

(defmethod event-handler ::host-battle [_e]
  (host-battle))

(defmethod event-handler ::battle-password-action [_e]
  (host-battle))


(defmethod event-handler ::leave-battle [_e]
  (client/send-message (:client @*state) "LEAVEBATTLE"))

(defmethod event-handler ::join-battle [_e]
  (let [{:keys [battles battle-password selected-battle]} @*state]
    (when selected-battle
      (client/send-message (:client @*state)
        (str "JOINBATTLE " selected-battle
             (when (= "1" (-> battles (get selected-battle) :battle-passworded)) ; TODO
               (str " " battle-password)))))))

(defn battles-buttons
  [{:keys [battle battles battle-password client selected-battle title engine-version mod-name map-name]}]
  {:fx/type :h-box
   :alignment :top-left
   :children
   (or
     (if battle
       [{:fx/type :button
         :text "Leave Battle"
         :on-action {:event/type ::leave-battle}}]
       (when client
         (concat
           (when (and selected-battle (-> battles (get selected-battle)))
             (let [needs-password (= "1" (-> battles (get selected-battle) :battle-passworded))] ; TODO
               [{:fx/type :button
                 :text "Join Battle"
                 :disable (boolean (and needs-password (string/blank? battle-password)))
                 :on-action {:event/type ::join-battle}}]))
           [{:fx/type :text-field
             :text (str battle-password)
             :prompt-text "Battle Password"
             :on-action {:event/type ::battle-password-action}
             :on-text-changed {:event/type ::battle-password-change}}
            {:fx/type :button
             :text "Host Battle"
             :on-action {:event/type ::host-battle}}
            {:fx/type :text-field
             :text (str title)
             :prompt-text "Battle Title"
             :on-action {:event/type ::host-battle}
             :on-text-changed {:event/type ::title-change}}
            {:fx/type :choice-box
             :value (str engine-version)
             :items ["103.0" "104.0"] ; TODO
             :on-value-changed {:event/type ::version-change}}
            {:fx/type :choice-box
             :value (str mod-name)
             :items ["Balanced Annihilation V9.79.4" "Balanced Annihilation V10.24"] ; TODO
             :on-value-changed {:event/type ::mod-change}}
            {:fx/type :choice-box
             :value (str map-name)
             :items ["Dworld Acidic" "Dworld Duo"] ; TODO
             :on-value-changed {:event/type ::map-change}}])))
     [])})

(defmethod event-handler ::battle-password-change [e]
  (swap! *state assoc :battle-password (:fx/event e)))

(defmethod event-handler ::title [e]
  (swap! *state assoc :title (:fx/event e)))

(defmethod event-handler ::version-change [e]
  (swap! *state assoc :engine-version (:fx/event e)))

(defmethod event-handler ::mod-change [e]
  (swap! *state assoc :mod-name (:fx/event e)))

(defmethod event-handler ::map-change [e]
  (swap! *state assoc :map-name (:fx/event e)))


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

(defn start-game []
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
        (let [^"[Ljava.lang.String;" cmdarray (into-array String command)
              ^"[Ljava.lang.String;" envp nil
              process (.exec runtime cmdarray envp isolation-dir)]
          (async/thread
            (with-open [^java.io.BufferedReader reader (io/reader (.getInputStream process))]
              (loop []
                (if-let [line (.readLine reader)]
                  (do
                    (info "(spring out)" line)
                    (recur))
                  (info "Spring stdout stream closed")))))
          (async/thread
            (with-open [^java.io.BufferedReader reader (io/reader (.getErrorStream process))]
              (loop []
                (if-let [line (.readLine reader)]
                  (do
                    (info "(spring err)" line)
                    (recur))
                  (info "Spring stderr stream closed")))))))
      (catch Exception e
        (warn e)))
    (client/send-message (:client @*state) "MYSTATUS 1")))

(defmethod event-handler ::start-battle [_e]
  (start-game))


(defn battle-table [{:keys [battle battles users username]}]
  (let [items (concat
                (mapv
                  (fn [[k v]] (assoc v :username k :user (get users k)))
                  (:users battle))
                (mapv
                  (fn [[k v]] (assoc v :username (str k "(" (:owner v) ")")))
                  (:bots battle)))
        {:keys [host-username]} (get battles (:battle-id battle))
        host-user (get users host-username)
        am-host (= username host-username)]
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
           :text "Spectator"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text ""
               :graphic
               {:fx/type :check-box
                :selected (not (:mode (:battle-status i)))
                :on-selected-changed {:event/type ::battle-spectate-change
                                      :id i}
                :disable (not (or am-host
                                  (= (:username i) username)
                                  (= (:owner i) username)))}})}}
          {:fx/type :table-column
           :text "Faction"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text ""
               :graphic
               {:fx/type :choice-box
                :value (str (:side (:battle-status i)))
                :on-value-changed {:event/type ::battle-side-change
                                   :id i}
                :items ["0" "1"]
                :disable (not= (:username i) username)}})}}
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
            :describe
            (fn [{:keys [team-color] :as i}]
              {:text ""
               :graphic
               {:fx/type :color-picker
                :value (format "#%03x" (if team-color (Integer/parseInt team-color) 0))
                :on-value-changed {:event/type ::battle-color-change
                                   :id i}
                :disable (not= (:username i) username)}})}}
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
            :describe
            (fn [i]
              {:text ""
               :graphic
               {:fx/type :choice-box
                :value (str (:id (:battle-status i)))
                :on-value-changed {:event/type ::battle-player-id-change
                                   :id i}
                :items (map str (take 16 (iterate inc 0)))
                :disable (not= (:username i) username)}})}}
          {:fx/type :table-column
           :text "Team ID"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text ""
               :graphic
               {:fx/type :choice-box
                :value (str (:ally (:battle-status i)))
                :on-value-changed {:event/type ::battle-ally-change
                                   :id i}
                :items (map str (take 16 (iterate inc 0)))
                :disable (not= (:username i) username)}})}}
          {:fx/type :table-column
           :text "Bonus"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text ""
               :graphic
               {:fx/type :text-field
                :disable (not am-host)
                :text-formatter
                {:fx/type :text-formatter
                 :value-converter :integer
                 :value (int (or (:handicap (:battle-status i)) 0))
                 :on-value-changed {:event/type ::battle-handicap-change
                                    :id i}}}})}}]}]
       (when battle
         [{:fx/type :h-box
           :alignment :top-left
           :children
           [{:fx/type fx.ext.node/with-tooltip-props
             :props
             {:tooltip
              {:fx/type :tooltip
               :text (if am-host
                       "You are the host"
                       (str "Waiting for host " host-username))}}
             :desc
             {:fx/type :button
              :text (str (if am-host "Start" "Join") " Game")
              :disable (boolean (and (not am-host)
                                     (not (-> host-user :client-status :ingame))))
              :on-action {:event/type ::start-battle}}}]}
          {:fx/type :h-box
           :alignment :top-left
           :children
           [{:fx/type :button
             :text "Add Bot"
             :on-action {:event/type ::add-bot}}]}]))}))

(defmethod event-handler ::battle-spectate-change
  [{:keys [id] :fx/keys [event]}]
  (client/send-message (:client @*state)
    (str "MYBATTLESTATUS "
         (client/encode-battle-status
           (assoc (:battle-status id) :mode (not event)))
         " " (:team-color id))))

(defmethod event-handler ::battle-side-change
  [{:keys [id] :fx/keys [event]}]
  (when-let [side (try (Integer/parseInt event) (catch Exception _e))]
    (client/send-message (:client @*state)
      (str "MYBATTLESTATUS "
           (client/encode-battle-status
             (assoc (:battle-status id) :side side))
           " " (:team-color id)))))

(defmethod event-handler ::battle-player-id-change
  [{:keys [id] :fx/keys [event]}]
  (when-let [player-id (try (Integer/parseInt event) (catch Exception _e))]
    (client/send-message (:client @*state)
      (str "MYBATTLESTATUS "
           (client/encode-battle-status
             (assoc (:battle-status id) :id player-id))
           " " (:team-color id)))))

(defmethod event-handler ::battle-ally-change
  [{:keys [id] :fx/keys [event]}]
  (when-let [ally (try (Integer/parseInt event) (catch Exception _e))]
    (client/send-message (:client @*state)
      (str "MYBATTLESTATUS "
           (client/encode-battle-status
             (assoc (:battle-status id) :ally ally))
           " " (:team-color id)))))

(defmethod event-handler ::battle-handicap-change
  [{:keys [id] :fx/keys [event]}]
  (when-let [handicap (max 0
                        (min 100
                          event))]
    (client/send-message (:client @*state)
      (str "HANDICAP "
           (:username id)
           " "
           handicap))))

(defmethod event-handler ::battle-color-change
  [{:keys [id] :fx/keys [event]}]
  (let [^javafx.scene.paint.Color color event
        color-int (colors/rgba-int
                    (colors/create-color
                      {:r (Math/round (* 255 (.getRed color)))
                       :g (Math/round (* 255 (.getGreen color)))
                       :b (Math/round (* 255 (.getBlue color)))
                       :a 0}))]
    (client/send-message (:client @*state)
      (str "MYBATTLESTATUS "
           (client/encode-battle-status (:battle-status id))
           " " color-int))))


(defn root-view
  [{{:keys [client client-deferred users battles battle battle-password selected-battle username
            password login-error title engine-version mod-name map-name last-failed-message]} :state}]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created (fn [node]
                 (println "on-created")
                 (.requestFocus node))
   :on-advanced (fn [_] (println "on-advanced"))
   :on-deleted (fn [_] (println "on-deleted"))
   :desc
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
                               :client-deferred client-deferred
                               :username username
                               :password password
                               :login-error login-error}
                              {:fx/type user-table
                               :users users}
                              {:fx/type battles-table
                               :battles battles
                               :users users}
                              {:fx/type battles-buttons
                               :battle battle
                               :battle-password battle-password
                               :battles battles
                               :client client
                               :selected-battle selected-battle
                               :title title
                               :engine-version engine-version
                               :mod-name mod-name
                               :map-name map-name}
                              {:fx/type battle-table
                               :battles battles
                               :battle battle
                               :users users
                               :username username}
                              {:fx/type :v-box
                               :alignment :center-left
                               :children
                               [{:fx/type :label
                                 :text (str last-failed-message)
                                 :style {:-fx-text-fill "#FF0000"}}]}]}}}})
