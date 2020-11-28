(ns spring-lobby
  (:require
    [clj-http.client :as clj-http]
    [cljfx.api :as fx]
    [cljfx.component :as fx.component]
    [cljfx.ext.node :as fx.ext.node]
    [cljfx.ext.table-view :as fx.ext.table-view]
    [cljfx.lifecycle :as fx.lifecycle]
    [clojure.core.async :as async]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :refer [pprint]]
    [clojure.set]
    [clojure.string :as string]
    [com.evocomputing.colors :as colors]
    [crouton.html :as html]
    [spring-lobby.client :as client]
    [spring-lobby.fs :as fs]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.http :as http]
    [spring-lobby.lua :as lua]
    [spring-lobby.rapid :as rapid]
    [spring-lobby.spring :as spring]
    [taoensso.timbre :as log]
    [version-clj.core :as version])
  (:import
    (java.nio.file CopyOption StandardCopyOption)
    (javafx.application Platform)
    (javafx.scene.paint Color)
    (manifold.stream SplicedStream)
    (org.apache.commons.io FileUtils)
    (org.apache.commons.io.input CountingInputStream))
  (:gen-class))


(set! *warn-on-reflection* true)


(def initial-state
  (or
    (try
      (let [config-file (io/file (fs/app-root) fs/config-filename)]
        (when (.exists config-file)
          (-> config-file slurp edn/read-string)))
      (catch Exception e
        (log/warn e "Exception loading initial state")))
    {}))


(def ^:dynamic *state
  (atom initial-state))


(def config-keys
  [:username :password :server-url :engine-version :mod-name :map-name
   :battle-title :battle-password
   :bot-username :bot-name :bot-version
   :engine-branch :maps-index-url])


(defn watch-config-state []
  (add-watch
    *state
    :config
    (fn [_k _ref old-state new-state]
      (let [old-config (select-keys old-state config-keys)
            new-config (select-keys new-state config-keys)]
        (when (not= old-config new-config)
          (log/debug "Updating config file")
          (let [app-root (io/file (fs/app-root))
                config-file (io/file app-root fs/config-filename)]
            (when-not (.exists app-root)
              (.mkdirs app-root))
            (spit config-file (with-out-str (pprint new-config)))))))))


(defmulti event-handler :event/type)


(defmethod event-handler ::reload-maps [_e]
  (future
    (swap! *state assoc :maps-cached nil)
    (swap! *state assoc :maps-cached (doall (fs/maps)))))


(defn cache-mods []
  (future
    (try
      (let [sdp-files (doall (rapid/sdp-files))
            try-inner-lua (fn [f filename]
                            (try
                              (when-let [inner (rapid/rapid-inner f filename)]
                                (let [contents (:contents inner)]
                                  (when-not (string/blank? contents)
                                    (lua/read-modinfo contents))))
                              (catch Exception e
                                (log/warn e "Error reading" filename "in" f))))
            rapid-mods
            (some->> sdp-files
                     (map (fn [f]
                            {::fs/source :rapid
                             :filename (.getAbsolutePath f)
                             :modinfo (try-inner-lua f "modinfo.lua")
                             :modoptions (try-inner-lua f "modoptions.lua")
                             :engineoptions (try-inner-lua f "engineoptions.lua")
                             :luaai (try-inner-lua f "luaai.lua")}))
                     (filter :modinfo))
            mods (doall (concat rapid-mods (fs/games)))]
        (swap! *state assoc :mods-cached mods))
      (catch Exception e
        (log/error e "Error loading mods")))))

(defmethod event-handler ::reload-mods [_e]
  (cache-mods))


(defmethod event-handler ::select-battle [e]
  (swap! *state assoc :selected-battle (-> e :fx/event :battle-id)))

(defn battles-table [{:keys [battles users]}]
  {:fx/type fx.ext.table-view/with-selection-props
   :props {:selection-mode :single
           :on-selected-item-changed {:event/type ::select-battle}}
   :desc
   {:fx/type :table-view
    :column-resize-policy :constrained ; TODO auto resize
    :items (vec (vals battles))
    :style {:-fx-max-height "240px"}
    :columns
    [{:fx/type :table-column
      :text "Battle Name"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (:battle-title i))})}}
     {:fx/type :table-column
      :text "Host"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (:host-username i))})}}
     {:fx/type :table-column
      :text "Status"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe
       (fn [i]
         (let [status (select-keys i [:battle-type :battle-passworded])]
           (if (:battle-passworded status)
             {:text ""
              :graphic
              {:fx/type font-icon/lifecycle
               :icon-literal "mdi-lock:16"}}
             {:text ""
              :graphic
              {:fx/type font-icon/lifecycle
               :icon-literal "mdi-lock-open:16"}})))}}
     {:fx/type :table-column
      :text "Country"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (:country (get users (:host-username i))))})}}
     {:fx/type :table-column
      :text "?"
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
      :text "Spectators"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (:battle-spectators i))})}}
     {:fx/type :table-column
      :text "Running"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (->> i :host-username (get users) :client-status :ingame str)})}}
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
      :text "Engine"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (:battle-engine i) " " (:battle-version i))})}}]}})

(defn user-table [{:keys [users]}]
  {:fx/type :table-view
   :column-resize-policy :constrained ; TODO auto resize
   :items (vec (vals users))
   :style {:-fx-max-height "240px"}
   :columns
   [{:fx/type :table-column
     :text "Username"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe (fn [i] {:text (str (:username i))})}}
    {:fx/type :table-column
     :text "Status"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe
      (fn [i]
        (let [status (select-keys (:client-status i) [:bot :access :away :ingame])]
          (cond
            (:bot status)
            {:text ""
             :graphic
             {:fx/type font-icon/lifecycle
              :icon-literal "mdi-robot:16"}}
            (:away status)
            {:text ""
             :graphic
             {:fx/type font-icon/lifecycle
              :icon-literal "mdi-sleep:16"}}
            (:access status)
            {:text ""
             :graphic
             {:fx/type font-icon/lifecycle
              :icon-literal "mdi-account-key:16"}}
            :else
            {:text ""
             :graphic
             {:fx/type font-icon/lifecycle
              :icon-literal "mdi-account:16"}}
            #_
            {:text (str status)
             :style {:-fx-font-family "monospace"}})))}}
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
      :describe (fn [i] {:text (str (:rank (:client-status i)))})}}
    {:fx/type :table-column
     :text "Lobby Client"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe (fn [i] {:text (str (:user-agent i))})}}]})

(defn update-disconnected []
  (log/warn (ex-info "stacktrace" {}) "Updating state after disconnect")
  (swap! *state dissoc
         :battle :battles :channels :client :client-deferred :my-channels :users
         :last-failed-message)
  nil)

(defmethod event-handler ::print-state [_e]
  (pprint *state))

(defmethod event-handler ::show-rapid-downloader [_e]
  (swap! *state assoc :show-rapid-downloader true))

(defmethod event-handler ::show-http-downloader [_e]
  (swap! *state assoc :show-http-downloader true))

(defmethod event-handler ::disconnect [_e]
  (let [state @*state]
    (when-let [client (:client state)]
      (when-let [f (:connected-loop state)]
        (future-cancel f))
      (when-let [f (:print-loop state)]
        (future-cancel f))
      (when-let [f (:ping-loop state)]
        (future-cancel f))
      (client/disconnect client)))
  (update-disconnected))

(defn connected-loop [state-atom client-deferred]
  (swap! state-atom assoc
         :connected-loop
         (future
           (try
             (let [^SplicedStream client @client-deferred]
               (client/connect state-atom client)
               (swap! state-atom assoc :client client :login-error nil)
               (loop []
                 (if (and client (not (.isClosed client)))
                   (when-not (Thread/interrupted)
                     (log/debug "Client is still connected")
                     (async/<!! (async/timeout 20000))
                     (recur))
                   (when-not (Thread/interrupted)
                     (log/info "Client was disconnected")
                     (update-disconnected))))
               (log/info "Connect loop closed"))
             (catch Exception e
               (log/error e "Connect loop error")
               (when-not (or (Thread/interrupted) (instance? java.lang.InterruptedException e))
                 (swap! state-atom assoc :login-error (str (.getMessage e)))
                 (update-disconnected))))
           nil)))

(defmethod event-handler ::connect [_e]
  (let [server-url (:server-url @*state)
        client-deferred (client/client server-url)]
    (swap! *state assoc :client-deferred client-deferred)
    (connected-loop *state client-deferred)))


(defn client-buttons
  [{:keys [client client-deferred username password login-error server-url]}]
  {:fx/type :h-box
   :alignment :top-left
   :children
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
     :prompt-text "Username"
     :disable (boolean (or client client-deferred))
     :on-text-changed {:event/type ::username-change}}
    {:fx/type :password-field
     :text password
     :prompt-text "Password"
     :disable (boolean (or client client-deferred))
     :on-text-changed {:event/type ::password-change}}
    {:fx/type :v-box
     :h-box/hgrow :always
     :alignment :center
     :children
     [{:fx/type :label
       :text (str login-error)
       :style {:-fx-text-fill "#FF0000"
               :-fx-max-width "360px"}}]}
    {:fx/type :text-field
     :text server-url
     :prompt-text "server:port"
     :disable (boolean (or client client-deferred))
     :on-text-changed {:event/type ::server-url-change}}]})


(defmethod event-handler ::username-change
  [{:fx/keys [event]}]
  (swap! *state assoc :username event))

(defmethod event-handler ::password-change
  [{:fx/keys [event]}]
  (swap! *state assoc :password event))

(defmethod event-handler ::server-url-change
  [{:fx/keys [event]}]
  (swap! *state assoc :server-url event))


(defn host-battle []
  (let [{:keys [client] :as state} @*state]
    (client/open-battle client
      (-> state
          (clojure.set/rename-keys {:battle-title :title})
          (select-keys [:battle-password :title :engine-version
                        :mod-name :map-name])
          (assoc :mod-hash -1
                :map-hash -1)))))

(defmethod event-handler ::host-battle [_e]
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

(defn map-list
  [{:keys [disable map-name maps-cached on-value-changed]}]
  (if maps-cached
    (let [map-names (mapv :map-name maps-cached)]
      {:fx/type :h-box
       :alignment :center-left
       :children
       (concat
         [{:fx/type :label
           :text " Map: "}
          {:fx/type :choice-box
           :value (str map-name)
           :items map-names
           :disable (boolean disable)
           :on-value-changed on-value-changed}
          {:fx/type fx.ext.node/with-tooltip-props
           :props
           {:tooltip
            {:fx/type :tooltip
             :show-delay [10 :ms]
             :text "Browse and download maps with http"}}
           :desc
           {:fx/type :button
            :on-action {:event/type ::show-http-downloader}
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal (str "mdi-magnify:16:white")}}}]
         (when (seq map-names)
           [{:fx/type fx.ext.node/with-tooltip-props
             :props
             {:tooltip
              {:fx/type :tooltip
               :show-delay [10 :ms]
               :text "Random map"}}
             :desc
             {:fx/type :button
              :on-action (assoc on-value-changed :map-name (rand-nth map-names))
              :graphic
              {:fx/type font-icon/lifecycle
               :icon-literal (str "mdi-dice-" (inc (rand-nth (take 6 (iterate inc 0)))) ":16:white")}}}])
         [{:fx/type fx.ext.node/with-tooltip-props
           :props
           {:tooltip
            {:fx/type :tooltip
             :show-delay [10 :ms]
             :text "Reload maps"}}
           :desc
           {:fx/type :button
            :on-action {:event/type ::reload-maps}
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal "mdi-refresh:16:white"}}}])})
    {:fx/type :v-box
     :alignment :center-left
     :children
     [{:fx/type :label
       :text "Loading maps..."}]}))

(defn battles-buttons
  [{:keys [battle battles battle-password client selected-battle
           battle-title engine-version mod-name map-name maps-cached engines-cached mods-cached]}]
  {:fx/type :h-box
   :alignment :top-left
   :children
   (concat
     (when battle
       [{:fx/type :button
         :text "Leave Battle"
         :on-action {:event/type ::leave-battle}}])
     (when (and (not battle) selected-battle (-> battles (get selected-battle)))
       (let [needs-password (= "1" (-> battles (get selected-battle) :battle-passworded))] ; TODO
         [{:fx/type :button
           :text "Join Battle"
           :disable (boolean (and needs-password (string/blank? battle-password)))
           :on-action {:event/type ::join-battle}}]))
     (when (and client (not battle))
       [{:fx/type :text-field
         :text (str battle-password)
         :prompt-text "Battle Password"
         :on-action {:event/type ::host-battle}
         :on-text-changed {:event/type ::battle-password-change}}
        {:fx/type :button
         :text "Host Battle"
         :on-action {:event/type ::host-battle}}
        {:fx/type :text-field
         :text (str battle-title)
         :prompt-text "Battle Title"
         :on-action {:event/type ::host-battle}
         :on-text-changed {:event/type ::battle-title-change}}])
     (when (not battle)
       [{:fx/type :h-box
         :alignment :center-left
         :children
         [{:fx/type :label
           :text " Engine: "}
          {:fx/type :choice-box
           :value (str engine-version)
           :items (or engines-cached [])
           :on-value-changed {:event/type ::version-change}}
          {:fx/type fx.ext.node/with-tooltip-props
           :props
           {:tooltip
            {:fx/type :tooltip
             :show-delay [10 :ms]
             :text "Browse and download engines with http"}}
           :desc
           {:fx/type :button
            :on-action {:event/type ::show-http-downloader}
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal (str "mdi-magnify:16:white")}}}]}
        {:fx/type :h-box
         :alignment :center-left
         :children
         (concat
           (if (seq mods-cached)
             [{:fx/type :label
               :alignment :center-left
               :text " Game: "}
              {:fx/type :choice-box
               :value (str mod-name)
               :items (or (some->> mods-cached
                                   (map :modinfo)
                                   (sort-by :version version/version-compare)
                                   (map (fn [modinfo]
                                          (str (:name modinfo) " " (:version modinfo)))))
                          [])
               :on-value-changed {:event/type ::mod-change}}]
             [{:fx/type :label
               :text "Loading games..."}])
           [{:fx/type fx.ext.node/with-tooltip-props
             :props
             {:tooltip
              {:fx/type :tooltip
               :show-delay [10 :ms]
               :text "Browse and download more with Rapid"}}
             :desc
             {:fx/type :button
              :on-action {:event/type ::show-rapid-downloader}
              :graphic
              {:fx/type font-icon/lifecycle
               :icon-literal (str "mdi-magnify:16:white")}}}
            {:fx/type fx.ext.node/with-tooltip-props
             :props
             {:tooltip
              {:fx/type :tooltip
               :show-delay [10 :ms]
               :text "Reload games"}}
             :desc
             {:fx/type :button
              :on-action {:event/type ::reload-mods}
              :graphic
              {:fx/type font-icon/lifecycle
               :icon-literal "mdi-refresh:16:white"}}}])}
        {:fx/type map-list
         :map-name map-name
         :maps-cached maps-cached
         :on-value-changed {:event/type ::map-change}}]))})


(defmethod event-handler ::battle-password-change
  [{:fx/keys [event]}]
  (swap! *state assoc :battle-password event))

(defmethod event-handler ::battle-title-change
  [{:fx/keys [event]}]
  (swap! *state assoc :battle-title event))

(defmethod event-handler ::version-change
  [{:fx/keys [event]}]
  (swap! *state assoc :engine-version event))

(defmethod event-handler ::mod-change
  [{:fx/keys [event]}]
  (swap! *state assoc :mod-name event))

(defmethod event-handler ::map-change
  [{:fx/keys [event] :keys [map-name]}]
  (swap! *state assoc :map-name (or map-name event)))

(defmethod event-handler ::battle-map-change
  [{:fx/keys [event] :keys [map-name]}]
  (swap! *state assoc :map-name (or map-name event))
  (let [spectator-count 0 ; TODO
        locked 0
        map-hash -1 ; TODO
        map-name (or map-name event)
        m (str "UPDATEBATTLEINFO " spectator-count " " locked " " map-hash " " map-name)]
    (client/send-message (:client @*state) m)))

(defmethod event-handler ::kick-battle
  [{:keys [bot-name username]}]
  (when-let [client (:client @*state)]
    (if bot-name
      (client/send-message client (str "REMOVEBOT " bot-name))
      (client/send-message client (str "KICKFROMBATTLE " username)))))


(defn random-color
  []
  (long (rand (* 255 255 255))))

(defn available-name [existing-names desired-name]
  (if-not (contains? (set existing-names) desired-name)
    desired-name
    (recur
      existing-names
      (if-let [[_ prefix n suffix] (re-find #"(.*)(\d+)(.*)" desired-name)]
        (let [nn (inc (edn/read-string n))]
          (str prefix nn suffix))
        (str desired-name 0)))))

(defmethod event-handler ::add-bot [{:keys [battle bot-username bot-name bot-version]}]
  (let [existing-bots (keys (:bots battle))
        bot-username (available-name existing-bots bot-username)
        bot-status (client/encode-battle-status
                     (assoc client/default-battle-status
                            :ready true
                            :mode 1
                            :sync 1
                            :side (rand-nth [0 1])))
        bot-color (random-color)
        message (str "ADDBOT " bot-username " " bot-status " " bot-color " " bot-name "|" bot-version)]
    (client/send-message (:client @*state) message)))

(defmethod event-handler ::change-bot-username
  [{:fx/keys [event]}]
  (swap! *state assoc :bot-username event))

(defmethod event-handler ::change-bot-name
  [{:keys [engine-version] :fx/keys [event]}]
  (let [bot-name event
        bot-version (-> (group-by :bot-name (fs/bots engine-version))
                        (get bot-name)
                        first
                        :bot-version)]
    (swap! *state assoc :bot-name bot-name :bot-version bot-version)))

(defmethod event-handler ::change-bot-version
  [{:fx/keys [event]}]
  (swap! *state assoc :bot-version event))


(defn script-txt []
  (let [{:keys [battle battles users username]} @*state
        battle (update battle :users #(into {} (map (fn [[k v]] [k (assoc v :username k :user (get users k))]) %)))
        battle (merge (get battles (:battle-id battle)) battle)
        script (spring/script-data battle {:myplayername username})
        script-txt (spring/script-txt script)]
    script-txt))


(defn copy-engine [engine-version]
  (if engine-version
    (let [source (io/file (fs/spring-root) "engine" engine-version)
          dest (io/file (fs/app-root) "spring" "engine" engine-version)]
      (if (.exists source)
        (do
          (FileUtils/forceMkdir dest)
          (log/info "Copying" source "to" dest)
          (FileUtils/copyDirectory source dest))
        (log/warn "No map file to copy from" (.getAbsolutePath source)
                  "to" (.getAbsolutePath dest))))
    (throw
      (ex-info "Missing map or engine to copy to isolation dir"
               {:engine-version engine-version}))))

#_
(copy-engine "103.0")

(defn copy-mod [mod-detail engine-version]
  (log/info "Mod detail:" (pr-str mod-detail))
  (let [mod-filename (:filename mod-detail)]
    (cond
      (not (and mod-filename engine-version))
      (throw
        (ex-info "Missing mod or engine to copy to isolation dir"
                 {:mod-filename mod-filename
                  :engine-version engine-version}))
      (= :rapid (::fs/source mod-detail))
      (let [sdp-decoded (rapid/decode-sdp (io/file mod-filename))
            source (io/file mod-filename)
            dest (io/file (fs/app-root) "spring" "engine" engine-version "packages" (.getName source))
            ^java.nio.file.Path source-path (.toPath source)
            ^java.nio.file.Path dest-path (.toPath dest)
            ^"[Ljava.nio.file.CopyOption;" options (into-array ^CopyOption
                                                     [StandardCopyOption/COPY_ATTRIBUTES
                                                      StandardCopyOption/REPLACE_EXISTING])]
        (.mkdirs dest)
        (java.nio.file.Files/copy source-path dest-path options)
        (doseq [item (:items sdp-decoded)]
          (let [md5 (:md5 item)
                pool-source (rapid/file-in-pool md5)
                pool-dest (rapid/file-in-pool (io/file (fs/app-root) "spring" "engine" engine-version) md5)
                ^java.nio.file.Path pool-source-path (.toPath pool-source)
                ^java.nio.file.Path pool-dest-path (.toPath pool-dest)]
            (log/info "Copying" pool-source-path "to" pool-dest-path)
            (.mkdirs pool-dest)
            (java.nio.file.Files/copy pool-source-path pool-dest-path options))))
      (= :archive (::fs/source mod-detail))
      (let [source (io/file (fs/spring-root) "games" mod-filename)
            dest (io/file (fs/app-root) "spring" "engine" engine-version "games" mod-filename)
            ^java.nio.file.Path source-path (.toPath source)
            ^java.nio.file.Path dest-path (.toPath dest)
            ^"[Ljava.nio.file.CopyOption;" options (into-array ^CopyOption
                                                     [StandardCopyOption/COPY_ATTRIBUTES
                                                      StandardCopyOption/REPLACE_EXISTING])]
        (if (.exists source)
          (do
            (.mkdirs dest)
            (java.nio.file.Files/copy source-path dest-path options))
          (log/warn "No mod file to copy from" (.getAbsolutePath source)
                    "to" (.getAbsolutePath dest)))))))

#_
(copy-mod "Balanced Annihilation V11.0.2")


(defn copy-map [map-filename engine-version]
  (if (and map-filename engine-version)
    (let [source (io/file (fs/spring-root) "maps" map-filename)
          dest (io/file (fs/app-root) "spring" "engine" engine-version "maps" map-filename)
          ^java.nio.file.Path source-path (.toPath source)
          ^java.nio.file.Path dest-path (.toPath dest)
          ^"[Ljava.nio.file.CopyOption;" options (into-array ^CopyOption
                                                   [StandardCopyOption/COPY_ATTRIBUTES
                                                    StandardCopyOption/REPLACE_EXISTING])]
      (if (.exists source)
        (do
          (.mkdirs dest)
          (java.nio.file.Files/copy source-path dest-path options))
        (log/warn "No map file to copy from" (.getAbsolutePath source)
                  "to" (.getAbsolutePath dest))))
    (throw
      (ex-info "Missing map or engine to copy to isolation dir"
               {:map-filename map-filename
                :engine-version engine-version}))))


#_
(copy-map "incandescence_1.5.sd7" "103.0")


(defn start-game []
  (try
    (log/info "Starting game")
    (let [{:keys [maps-cached mods-cached] :as state} @*state
          battle (-> state
                     :battles
                     (get (-> state :battle :battle-id)))
          {:keys [battle-map battle-version battle-modname]} battle
          _ (copy-engine battle-version)
          mod-detail (some->> mods-cached
                              (filter (comp #{battle-modname} (fn [modinfo] (str (:name modinfo) " " (:version modinfo))) :modinfo))
                              first)
          _ (copy-mod mod-detail battle-version)
          map-filename (->> maps-cached
                            (filter (comp #{battle-map} :map-name))
                            first
                            :filename)
          _ (copy-map map-filename battle-version)
          script-txt (script-txt)
          isolation-dir (io/file (fs/app-root) "spring" "engine" battle-version)
          engine-file (io/file isolation-dir (fs/spring-executable))
          _ (log/info "Engine executable" engine-file)
          script-file (io/file (fs/app-root) "spring" "script.txt")
          script-file-param (fs/wslpath script-file)
          isolation-dir-param (fs/wslpath isolation-dir)]
      (spit script-file script-txt)
      (log/info "Wrote script to" script-file)
      (let [command [(.getAbsolutePath engine-file)
                     "--isolation-dir" isolation-dir-param
                     script-file-param]
            runtime (Runtime/getRuntime)]
        (log/info "Running '" command "'")
        (let [^"[Ljava.lang.String;" cmdarray (into-array String command)
              ^"[Ljava.lang.String;" envp (fs/envp)
              process (.exec runtime cmdarray envp isolation-dir)]
          (client/send-message (:client @*state) "MYSTATUS 1") ; TODO full status
          (async/thread
            (with-open [^java.io.BufferedReader reader (io/reader (.getInputStream process))]
              (loop []
                (if-let [line (.readLine reader)]
                  (do
                    (log/info "(spring out)" line)
                    (recur))
                  (log/info "Spring stdout stream closed")))))
          (async/thread
            (with-open [^java.io.BufferedReader reader (io/reader (.getErrorStream process))]
              (loop []
                (if-let [line (.readLine reader)]
                  (do
                    (log/info "(spring err)" line)
                    (recur))
                  (log/info "Spring stderr stream closed")))))
          (future
            (.waitFor process)
            (client/send-message (:client @*state) "MYSTATUS 0"))))) ; TODO full status
    (catch Exception e
      (log/error e "Error starting game")
      (client/send-message (:client @*state) "MYSTATUS 0")))) ; TODO full status

(defmethod event-handler ::start-battle [_e]
  (start-game))



(def start-pos-r 10.0)
(def map-multiplier 8.0)
(def minimap-width 256)
(def minimap-height 256)


(defn minimap-starting-points
  [map-details]
  (let [{:keys [map-width map-height]} (-> map-details :smf :header)
        teams (or (->> map-details :mapinfo :teams
                       (map
                         (fn [[k v]]
                           [k
                            (-> v
                                (update-in [:startpos :x] edn/read-string)
                                (update-in [:startpos :z] edn/read-string))]))
                       seq)
                  (->> map-details
                       :smd
                       :map
                       (filter (comp #(string/starts-with? % "team") name first))
                       (map
                         (fn [[k {:keys [startposx startposz]}]]
                           [k {:startpos {:x startposx :z startposz}}]))
                       (into {})))]
    (->> teams
         (map
           (fn [[_team {:keys [startpos]}]]
             (when-let [{:keys [x z]} startpos]
               (if (and (number? x) (number? z))
                 {:x (- (* (/ x (* map-multiplier map-width)) minimap-width)
                        (/ start-pos-r 2))
                  :y (- (* (/ z (* map-multiplier map-height)) minimap-height)
                        (/ start-pos-r 2))}
                 (log/warn "Bad starting pos" startpos)))))
         (filter some?))))

; https://github.com/cljfx/cljfx/issues/76#issuecomment-645563116
(def ext-recreate-on-key-changed
  "Extension lifecycle that recreates its component when lifecycle's key is changed

  Supported keys:
  - `:key` (required) - a value that determines if returned component should be recreated
  - `:desc` (required) - a component description with additional lifecycle semantics"
  (reify fx.lifecycle/Lifecycle
    (create [_ {:keys [key desc]} opts]
      (with-meta {:key key
                  :child (fx.lifecycle/create fx.lifecycle/dynamic desc opts)}
                 {`fx.component/instance #(-> % :child fx.component/instance)}))
    (advance [this component {:keys [key desc] :as this-desc} opts]
      (if (= (:key component) key)
        (update component :child #(fx.lifecycle/advance fx.lifecycle/dynamic % desc opts))
        (do (fx.lifecycle/delete this component opts)
            (fx.lifecycle/create this this-desc opts))))
    (delete [_ component opts]
      (fx.lifecycle/delete fx.lifecycle/dynamic (:child component) opts))))

(defn battle-buttons
  [{:keys [am-host host-user host-username maps-cached battle-map bot-username bot-name bot-version
           engine-version map-details battle battles username users mods-cached]}]
  (let [battle-modname (:battle-modname (get battles (:battle-id battle)))
        mod-details (some->> mods-cached
                             (filter (comp #{battle-modname}
                                           (fn [modinfo]
                                             (str (:name modinfo) " " (:version modinfo)))
                                           :modinfo))
                             first)]
    {:fx/type :h-box
     :children
     (concat
       [{:fx/type :v-box
         :children
         [{:fx/type :h-box
           :alignment :top-left
           :children
           [{:fx/type fx.ext.node/with-tooltip-props
             :props
             {:tooltip
              {:fx/type :tooltip
               :show-delay [10 :ms]
               :text (if am-host
                       "You are the host"
                       (str "Waiting for host " host-username))}}
             :desc
             {:fx/type :button
              :text (str (if am-host "Start" "Join") " Game")
              :disable (boolean (and (not am-host)
                                     (not (-> host-user :client-status :ingame))))
              :on-action {:event/type ::start-battle}}}
            {:fx/type map-list
             :disable (not am-host)
             :map-name battle-map
             :maps-cached maps-cached
             :on-value-changed {:event/type ::battle-map-change}}]}
          {:fx/type :h-box
           :alignment :top-left
           :children
           [{:fx/type :button
             :text "Add Bot"
             :disable (or (string/blank? bot-username)
                          (string/blank? bot-name)
                          (string/blank? bot-version))
             :on-action {:event/type ::add-bot
                         :battle battle
                         :bot-username bot-username
                         :bot-name bot-name
                         :bot-version bot-version}}
            {:fx/type :text-field
             :prompt-text "Bot Name"
             :text (str bot-username)
             :on-text-changed {:event/type ::change-bot-username}}
            {:fx/type :choice-box
             :value (str bot-name)
             :on-value-changed {:event/type ::change-bot-name
                                :engine-version engine-version}
             :items (map :bot-name (fs/bots engine-version))}
            {:fx/type :choice-box
             :value (str bot-version)
             :on-value-changed {:event/type ::change-bot-version}
             :items (map :bot-version
                         (or (get (group-by :bot-name (fs/bots engine-version))
                                  bot-name)
                             []))}]}
          {:fx/type :pane
           :v-box/vgrow :always}
          {:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :button
             :text "Randomize Colors"
             :on-action {:event/type ::battle-randomize-colors
                         :battle battle
                         :users users
                         :username username}}]}
          {:fx/type :pane
           :v-box/vgrow :always}
          {:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :button
             :text "FFA"
             :on-action {:event/type ::battle-teams-ffa
                         :battle battle
                         :users users
                         :username username}}
            {:fx/type :button
             :text "2 teams"
             :on-action {:event/type ::battle-teams-2
                         :battle battle
                         :users users
                         :username username}}
            {:fx/type :button
             :text "3 teams"
             :on-action {:event/type ::battle-teams-3
                         :battle battle
                         :users users
                         :username username}}
            {:fx/type :button
             :text "4 teams"
             :on-action {:event/type ::battle-teams-4
                         :battle battle
                         :users users
                         :username username}}]}
          {:fx/type :pane
           :v-box/vgrow :always}
          {:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :label
             :alignment :center-left
             :text "Start Positions: "}
            {:fx/type :choice-box
             :value (->> battle
                         :scripttags
                         :game
                         :startpostype
                         (or 1)
                         (get spring/startpostypes)
                         str)
             :items (map str (vals spring/startpostypes))
             :disable (not am-host)
             :on-value-changed {:event/type ::battle-startpostype-change}}]}
          {:fx/type :pane
           :v-box/vgrow :always}
          {:fx/type :h-box
           :alignment :center-left
           :style {:-fx-font-size 24}
           :children
           [(let [{:keys [battle-status] :as me} (-> battle :users (get username))]
              {:fx/type :check-box
               :selected (-> battle-status :ready boolean)
               :style {:-fx-padding "10px"}
               :on-selected-changed (merge me
                                      {:event/type ::battle-ready-change
                                       :username username})})
            {:fx/type :label
             :alignment :center-left
             :text " Ready"}]}]}
        {:fx/type :pane
         :h-box/hgrow :always}
        #_
        {:fx/type :v-box
         :alignment :top-left
         :children
         [{:fx/type :label
           :text "mod details"}
          {:fx/type :text-area
           :editable false
           :text (with-out-str (pprint mod-details))
           :style {:-fx-font-family "monospace"}
           :v-box/vgrow :always}]}
        {:fx/type :table-view
         :h-box/hgrow :always
         :column-resize-policy :constrained
         :items (or (some->> mod-details
                             :modoptions
                             (map second))
                    [])
         :columns
         [{:fx/type :table-column
           :text "Key"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text (str (:key i))})}}
          {:fx/type :table-column
           :text "Type"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text (str (:type i))})}}
          {:fx/type :table-column
           :text "Value"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              (if (= "bool" (:type i))
                {:text ""
                 :graphic
                 {:fx/type ext-recreate-on-key-changed
                  :key (str (:key i))
                  :desc
                  {:fx/type :check-box
                   :selected (boolean (edn/read-string (or (-> battle :scripttags :game :modoptions (get (:key i)))
                                                           (:def i))))
                   :on-selected-changed {:event/type ::modoption-change
                                         :modoption-key (:key i)}
                   :disable (not am-host)}}}
                {:text (str (:def i))}))}}]}
        {:fx/type :v-box
         :alignment :top-left
         :children
         [{:fx/type :label
           :text "scripttags"}
          {:fx/type :text-area
           :editable false
           :text (with-out-str (pprint (:scripttags battle)))
           :style {:-fx-font-family "monospace"}
           :v-box/vgrow :always}]}
        {:fx/type :v-box
         :alignment :top-left
         :children
         [{:fx/type :label
           :text "script.txt preview"}
          {:fx/type :text-area
           :editable false
           :text (str (string/replace (script-txt) #"\t" "  "))
           :style {:-fx-font-family "monospace"}
           :v-box/vgrow :always}]}
        #_
        {:fx/type :v-box
         :alignment :top-left
         :children
         [{:fx/type :label
           :text "Map Details"}
          {:fx/type :text-area
           :editable false
           :text (with-out-str (pprint map-details))
           :style {:-fx-font-family "monospace"}
           :v-box/vgrow :always}]}]
      (let [image-file (io/file (fs/map-minimap battle-map))]
        (when (.exists image-file)
          [{:fx/type :v-box
            :alignment :top-left
            :children
            [{:fx/type :label
              :text "Minimap"}
             {:fx/type :stack-pane
              :children
              (concat
                [{:fx/type :image-view
                  :image {:is (let [image-file (io/file (fs/map-minimap battle-map))]
                                (when (.exists image-file)
                                  (io/input-stream image-file)))}
                  :fit-width minimap-width
                  :fit-height minimap-height}
                 {:fx/type :canvas
                  :width minimap-width
                  :height minimap-height
                  :draw
                  (fn [^javafx.scene.canvas.Canvas canvas]
                    (let [gc (.getGraphicsContext2D canvas)
                          starting-points (minimap-starting-points map-details)]
                      (.clearRect gc 0 0 minimap-width minimap-height)
                      (.setFill gc Color/RED)
                      (doseq [{:keys [x y]} starting-points]
                        (.fillOval gc x y start-pos-r start-pos-r))))}])}]}])))}))


(defn battle-players-and-bots
  "Returns the sequence of all players and bots for a battle."
  [{:keys [battle users]}]
  (concat
    (mapv
      (fn [[k v]] (assoc v :username k :user (get users k)))
      (:users battle))
    (mapv
      (fn [[k v]]
        (assoc v
               :bot-name k
               :user {:client-status {:bot true}}))
      (:bots battle))))


(defn update-battle-status
  "Sends a message to update battle status for yourself or a bot of yours."
  [{:keys [client]} {:keys [is-bot id]} battle-status team-color]
  (when client
    (let [player-name (or (:bot-name id) (:username id))
          prefix (if is-bot
                   (str "UPDATEBOT " player-name) ; TODO normalize
                   "MYBATTLESTATUS")]
      (log/debug player-name (pr-str battle-status) team-color)
      (client/send-message client
        (str prefix
             " "
             (client/encode-battle-status battle-status)
             " "
             team-color)))))

(defn update-color [id {:keys [is-me is-bot] :as opts} color-int]
  (if (or is-me is-bot)
    (update-battle-status @*state (assoc opts :id id) (:battle-status id) color-int)
    (client/send-message (:client @*state)
      (str "FORCETEAMCOLOR " (:username id) " " color-int))))

(defn update-team [id {:keys [is-me is-bot] :as opts} player-id]
  (if (or is-me is-bot)
    (update-battle-status @*state (assoc opts :id id) (assoc (:battle-status id) :id player-id) (:team-color id))
    (client/send-message (:client @*state)
      (str "FORCETEAMNO " (:username id) " " player-id))))

(defn update-ally [id {:keys [is-me is-bot] :as opts} ally]
  (if (or is-me is-bot)
    (update-battle-status @*state (assoc opts :id id) (assoc (:battle-status id) :ally ally) (:team-color id))
    (client/send-message (:client @*state)
      (str "FORCEALLYNO " (:username id) " " ally))))

(defn apply-battle-status-changes
  [id {:keys [is-me is-bot] :as opts} status-changes]
  (if (or is-me is-bot)
    (update-battle-status @*state (assoc opts :id id) (merge (:battle-status id) status-changes) (:team-color id))
    (doseq [[k v] status-changes]
      (let [msg (case k
                  :id "FORCETEAMNO"
                  :ally "FORCEALLYNO")]
        (client/send-message (:client @*state) (str msg " " (:username id) " " v))))))


(defmethod event-handler ::battle-randomize-colors
  [e]
  (let [players-and-bots (battle-players-and-bots e)]
    (doseq [id players-and-bots]
      (let [is-bot (boolean (:bot-name id))
            is-me (= (:username e) (:username id))]
        (update-color id {:is-me is-me :is-bot is-bot} (random-color))))))

(defmethod event-handler ::battle-teams-ffa
  [e]
  (let [players-and-bots (battle-players-and-bots e)]
    (doall
      (map-indexed
        (fn [i id]
          (let [is-bot (boolean (:bot-name id))
                is-me (= (:username e) (:username id))]
            (apply-battle-status-changes id {:is-me is-me :is-bot is-bot} {:id i :ally i})))
        players-and-bots))))

(defn n-teams [e n]
  (let [players-and-bots (battle-players-and-bots e)
        per-partition (int (Math/ceil (/ (count players-and-bots) n)))
        by-ally (->> players-and-bots
                     (shuffle)
                     (map-indexed vector)
                     (partition-all per-partition)
                     vec)]
    (log/debug by-ally)
    (doall
      (map-indexed
        (fn [a players]
          (log/debug a (pr-str players))
          (doall
            (map
              (fn [[i id]]
                (let [is-bot (boolean (:bot-name id))
                      is-me (= (:username e) (:username id))]
                  (apply-battle-status-changes id {:is-me is-me :is-bot is-bot} {:id i :ally a})))
              players)))
        by-ally))))

(defmethod event-handler ::battle-teams-2
  [e]
  (n-teams e 2))

(defmethod event-handler ::battle-teams-3
  [e]
  (n-teams e 3))

(defmethod event-handler ::battle-teams-4
  [e]
  (n-teams e 4))


(defn fix-color
  "Returns the rgb int color represention for the given Spring bgr int color."
  [spring-color-int]
  (let [[r g b _a] (:rgba (colors/create-color spring-color-int))
        reversed (colors/create-color
                   {:r b
                    :g g
                    :b r})
        spring-int (colors/rgb-int reversed)]
    spring-int))

(defn spring-color
  "Returns the spring bgr int color format from a javafx color."
  [^javafx.scene.paint.Color color]
  (colors/rgba-int
    (colors/create-color
      {:r (Math/round (* 255 (.getBlue color)))  ; switch blue to red
       :g (Math/round (* 255 (.getGreen color)))
       :b (Math/round (* 255 (.getRed color)))   ; switch red to blue
       :a 0})))

#_
(colors/create-color
  (colors/rgb-int-to-components 0))
#_
(colors/create-color 0)


(defn nickname [{:keys [bot-name owner username]}]
  (if bot-name
    (str bot-name (when owner (str " (" owner ")")))
    (str username)))


(defn battle-table
  [{:keys [battle battles users username maps-cached] :as state}]
  (let [{:keys [host-username battle-map]} (get battles (:battle-id battle))
        host-user (get users host-username)
        am-host (= username host-username)
        map-details (->> maps-cached
                         (filter (comp #{battle-map} :map-name))
                         first)]
    {:fx/type :v-box
     :alignment :top-left
     :children
     (concat
       [{:fx/type :table-view
         :column-resize-policy :constrained ; TODO auto resize
         :items (battle-players-and-bots state)
         :columns
         [{:fx/type :table-column
           :text "Nickname"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [{:keys [owner] :as id}]
              (merge
                {:text (nickname id)}
                (when (and (not= username (:username id))
                           (or am-host
                               (= owner username)))
                  {:graphic
                   {:fx/type :button
                    :on-action
                    (merge
                      {:event/type ::kick-battle}
                      (select-keys id [:username :bot-name]))
                    :graphic
                    {:fx/type font-icon/lifecycle
                     :icon-literal "mdi-account-remove:16:white"}}})))}}
          {:fx/type :table-column
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
            :describe
            (fn [i]
              (let [status (merge
                             (select-keys (:client-status (:user i)) [:bot])
                             (select-keys (:battle-status i) [:ready])
                             {:host (= (:username i) host-username)})]
                (cond
                  (:bot status)
                  {:text ""
                   :graphic
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-robot:16"}}
                  (:ready status)
                  {:text ""
                   :graphic
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-account-check:16"}}
                  (:host status)
                  {:text ""
                   :graphic
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-account-key:16"}}
                  :else
                  {:text ""
                   :graphic
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-account:16"}})))}}
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
               {:fx/type ext-recreate-on-key-changed
                :key (nickname i)
                :desc
                {:fx/type :check-box
                 :selected (not (:mode (:battle-status i)))
                 :on-selected-changed {:event/type ::battle-spectate-change
                                       :is-me (= (:username i) username)
                                       :is-bot (-> i :user :client-status :bot)
                                       :id i}
                 :disable (not (or (and am-host (:mode (:battle-status i)))
                                   (= (:username i) username)
                                   (= (:owner i) username)))}}})}}
          {:fx/type :table-column
           :text "Faction"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text ""
               :graphic
               {:fx/type ext-recreate-on-key-changed
                :key (nickname i)
                :desc
                {:fx/type :choice-box
                 :value (->> i :battle-status :side (get spring/sides) str)
                 :on-value-changed {:event/type ::battle-side-changed
                                    :is-me (= (:username i) username)
                                    :is-bot (-> i :user :client-status :bot)
                                    :id i}
                 :items (vals spring/sides)
                 :disable (not (or am-host
                                   (= (:username i) username)
                                   (= (:owner i) username)))}}})}}
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
               {:fx/type ext-recreate-on-key-changed
                :key (nickname i)
                :desc
                {:fx/type :color-picker
                 :value (format "#%06x" (fix-color (if team-color (Integer/parseInt team-color) 0)))
                 :on-action {:event/type ::battle-color-action
                             :is-me (= (:username i) username)
                             :is-bot (-> i :user :client-status :bot)
                             :id i}
                 :disable (not (or am-host
                                   (= (:username i) username)
                                   (= (:owner i) username)))}}})}}
          {:fx/type :table-column
           :text "Team"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text ""
               :graphic
               {:fx/type ext-recreate-on-key-changed
                :key (nickname i)
                :desc
                {:fx/type :choice-box
                 :value (str (:id (:battle-status i)))
                 :on-value-changed {:event/type ::battle-team-changed
                                    :is-me (= (:username i) username)
                                    :is-bot (-> i :user :client-status :bot)
                                    :id i}
                 :items (map str (take 16 (iterate inc 0)))
                 :disable (not (or am-host
                                   (= (:username i) username)
                                   (= (:owner i) username)))}}})}}
          {:fx/type :table-column
           :text "Ally"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text ""
               :graphic
               {:fx/type ext-recreate-on-key-changed
                :key (nickname i)
                :desc
                {:fx/type :choice-box
                 :value (str (:ally (:battle-status i)))
                 :on-value-changed {:event/type ::battle-ally-changed
                                    :is-me (= (:username i) username)
                                    :is-bot (-> i :user :client-status :bot)
                                    :bot (-> i :user :client-status :bot)
                                    :id i}
                 :items (map str (take 16 (iterate inc 0)))
                 :disable (not (or am-host
                                   (= (:username i) username)
                                   (= (:owner i) username)))}}})}}
          {:fx/type :table-column
           :text "Bonus"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text ""
               :graphic
               {:fx/type ext-recreate-on-key-changed
                :key (nickname i)
                :desc
                {:fx/type :text-field
                 :disable (not am-host)
                 :text-formatter
                 {:fx/type :text-formatter
                  :value-converter :integer
                  :value (int (or (:handicap (:battle-status i)) 0))
                  :on-value-changed {:event/type ::battle-handicap-change
                                     :id i}}}}})}}]}] ; TODO update bot
       (when battle
         [(merge
            {:fx/type battle-buttons
             :am-host am-host
             :battle-map battle-map
             :host-user host-user
             :map-details map-details}
            (select-keys state [:battle :username :host-username :maps-cached
                                :bot-username :bot-name :bot-version :engine-version
                                :mods-cached :battles]))]))}))


(defmethod event-handler ::battle-startpostype-change
  [{:fx/keys [event]}]
  (let [startpostype (get spring/startpostypes-by-name event)]
    (swap! *state assoc-in [:battle :scripttags :startpostype] startpostype)
    (client/send-message (:client @*state) (str "SETSCRIPTTAGS game/startpostype=" startpostype))))

(defmethod event-handler ::modoption-change
  [{:keys [modoption-key] :fx/keys [event]}]
  (let [k (keyword modoption-key)
        value (str event)]
    (swap! *state assoc-in [:battle :scripttags :game :modoptions k] (str event))
    (client/send-message (:client @*state) (str "SETSCRIPTTAGS game/modoptions/" modoption-key "=" value))))

(defmethod event-handler ::battle-ready-change
  [{:fx/keys [event] :keys [battle-status team-color] :as id}]
  (update-battle-status @*state {:id id} (assoc battle-status :ready event) team-color))


(defmethod event-handler ::battle-spectate-change
  [{:keys [id is-me is-bot] :fx/keys [event] :as data}]
  (if (or is-me is-bot)
    (update-battle-status @*state data
      (assoc (:battle-status id) :mode (not event))
      (:team-color id))
    (client/send-message (:client @*state)
      (str "FORCESPECTATORMODE " (:username id)))))

(defmethod event-handler ::battle-side-changed
  [{:keys [id] :fx/keys [event] :as data}]
  (when-let [side (try (Integer/parseInt event) (catch Exception _e))]
    (if (not= side (-> id :battle-status :side))
      (do
        (log/info "Updating side for" id "from" (-> id :battle-status :side) "to" side)
        (update-battle-status @*state data (assoc (:battle-status id) :side side) (:team-color id)))
      (log/debug "No change for side"))))

(defmethod event-handler ::battle-team-changed
  [{:keys [id] :fx/keys [event] :as data}]
  (when-let [player-id (try (Integer/parseInt event) (catch Exception _e))]
    (if (not= player-id (-> id :battle-status :id))
      (do
        (log/info "Updating team for" id "from" (-> id :battle-status :side) "to" player-id)
        (update-team id data player-id))
      (log/debug "No change for team"))))

(defmethod event-handler ::battle-ally-changed
  [{:keys [id] :fx/keys [event] :as data}]
  (when-let [ally (try (Integer/parseInt event) (catch Exception _e))]
    (if (not= ally (-> id :battle-status :ally))
      (do
        (log/info "Updating ally for" id "from" (-> id :battle-status :ally) "to" ally)
        (update-ally id data ally))
      (log/debug "No change for ally"))))

(defmethod event-handler ::battle-handicap-change
  [{:keys [id] :fx/keys [event] :as e}]
  (log/debug (:event/type e))
  (when-let [handicap (max 0
                        (min 100
                          event))]
    (client/send-message (:client @*state)
      (str "HANDICAP "
           (:username id)
           " "
           handicap))))

(defmethod event-handler ::battle-color-action
  [{:keys [id] :fx/keys [^javafx.event.Event event] :as opts}]
  (let [javafx-color (.getValue (.getSource event))
        color-int (spring-color javafx-color)]
    (update-color id opts color-int)))

(defmethod event-handler ::rapid-repo-change
  [{:fx/keys [event]}]
  (swap! *state assoc :rapid-repo event)
  (future
    (let [versions (->> (rapid/versions event)
                        (sort-by :version version/version-compare)
                        reverse
                        doall)]
      (swap! *state assoc :rapid-versions-cached versions))))

(defmethod event-handler ::rapid-download
  [{:keys [engine-version rapid-id]}]
  (swap! *state assoc-in [:rapid-download rapid-id] {:running true
                                                     :message "Preparing to run pr-downloader"})
  (future
    (let [pr-downloader-file (io/file (fs/spring-root) "engine" engine-version (fs/executable "pr-downloader"))
          command [(.getAbsolutePath pr-downloader-file)
                   "--filesystem-writepath" (fs/wslpath (fs/spring-root))
                   "--rapid-download" rapid-id]
          runtime (Runtime/getRuntime)]
      (log/info "Running '" command "'")
      (let [^"[Ljava.lang.String;" cmdarray (into-array String command)
            ^"[Ljava.lang.String;" envp nil
            process (.exec runtime cmdarray envp (fs/spring-root))]
        (future
          (with-open [^java.io.BufferedReader reader (io/reader (.getInputStream process))]
            (loop []
              (if-let [line (.readLine reader)]
                (do
                  (swap! *state assoc-in [:rapid-download rapid-id :message] line)
                  (log/info "(pr-downloader" rapid-id "out)" line)
                  (recur))
                (log/info "pr-downloader" rapid-id "stdout stream closed")))))
        (future
          (with-open [^java.io.BufferedReader reader (io/reader (.getErrorStream process))]
            (loop []
              (if-let [line (.readLine reader)]
                (do
                  (swap! *state assoc-in [:rapid-download rapid-id :message] line)
                  (log/info "(pr-downloader" rapid-id "err)" line)
                  (recur))
                (log/info "pr-downloader" rapid-id "stderr stream closed")))))
        (.waitFor process)
        (swap! *state assoc-in [:rapid-download rapid-id :running] false)
        (swap! *state assoc :sdp-files-cached (doall (rapid/sdp-files)))))))

(defmethod event-handler ::engine-branch-change
  [{:fx/keys [event]}]
  (swap! *state assoc :engine-branch event)
  (future
    (log/debug "Getting engine versions for branch" event)
    (let [versions (->> (http/springrts-buildbot-files [event])
                        (sort-by :filename version/version-compare)
                        reverse
                        doall)]
      (log/debug "Got engine versions" (pr-str versions))
      (swap! *state assoc :engine-versions-cached versions))))

(defmethod event-handler ::maps-index-change
  [{:fx/keys [event]}]
  (swap! *state assoc :maps-index-url event)
  (future
    (log/debug "Getting maps from" event)
    (let [map-files (->> (http/files (html/parse event))
                         (sort-by :filename)
                         doall)]
      (log/debug "Got maps" (pr-str map-files))
      (swap! *state assoc :map-files-cache map-files))))


; https://github.com/dakrone/clj-http/pull/220/files
(defn print-progress-bar
  "Render a simple progress bar given the progress and total. If the total is zero
   the progress will run as indeterminated."
  ([progress total] (print-progress-bar progress total {}))
  ([progress total {:keys [bar-width]
                    :or   {bar-width 50}}]
   (if (pos? total)
     (let [pct (/ progress total)
           render-bar (fn []
                        (let [bars (Math/floor (* pct bar-width))
                              pad (- bar-width bars)]
                          (str (clojure.string/join (repeat bars "="))
                               (clojure.string/join (repeat pad " ")))))]
       (print (str "[" (render-bar) "] "
                   (int (* pct 100)) "% "
                   progress "/" total)))
     (let [render-bar (fn [] (clojure.string/join (repeat bar-width "-")))]
       (print (str "[" (render-bar) "] "
                   progress "/?"))))))

(defn insert-at
  "Addes value into a vector at an specific index."
  [v idx val]
  (-> (subvec v 0 idx)
      (conj val)
      (into (subvec v idx))))

(defn insert-after
  "Finds an item into a vector and adds val just after it.
   If needle is not found, the input vector will be returned."
  [v needle val]
  (let [index (.indexOf v needle)]
    (if (neg? index)
      v
      (insert-at v (inc index) val))))

(defn wrap-downloaded-bytes-counter
  "Middleware that provides an CountingInputStream wrapping the stream output"
  [client]
  (fn [req]
    (let [resp (client req)
          counter (CountingInputStream. (:body resp))]
      (merge resp {:body                     counter
                   :downloaded-bytes-counter counter}))))


(defmethod event-handler ::http-download
  [{:keys [dest url]}]
  (swap! *state assoc-in [:http-download url] {:running true
                                               :message "Preparing to download..."})
  (log/info "Request to download" url "to" dest)
  (future
    (try
      (clj-http/with-middleware
        (-> clj-http/default-middleware
            (insert-after clj-http/wrap-url wrap-downloaded-bytes-counter)
            (conj clj-http/wrap-lower-case-headers))
        (let [request (clj-http/get url {:as :stream})
              length (Integer. (get-in request [:headers "content-length"] 0))
              buffer-size (* 1024 10)]
          (with-open [input (:body request)
                      output (io/output-stream dest)]
            (let [buffer (make-array Byte/TYPE buffer-size)
                  counter (:downloaded-bytes-counter request)]
              (loop []
                (let [size (.read input buffer)]
                  (when (pos? size)
                    (.write output buffer 0 size)
                    (when counter
                      (let [msg (with-out-str
                                  (print-progress-bar
                                    (.getByteCount counter)
                                    length))]
                        (swap! *state assoc-in [:http-download url :message] msg)))
                    (recur))))))))
      (catch Exception e
        (log/error e "Error downloading" url "to" dest))
      (finally
        (swap! *state assoc-in [:http-download url :running] false)
        (log/info "Finished downloading" url "to" dest)))))


(defn root-view
  [{{:keys [client client-deferred users battles battle battle-password selected-battle username
            password login-error battle-title engine-version mod-name map-name last-failed-message
            maps-cached
            bot-username bot-name bot-version
            server-url standalone
            rapid-repo rapid-download sdp-files-cached rapid-repos-cached engines-cached
            rapid-versions-cached
            show-rapid-downloader
            engine-branch engine-versions-cached http-download
            maps-index-url map-files-cache
            show-http-downloader
            mods-cached]}
    :state}]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created (fn [& args]
                 (log/debug "on-created" args)
                 (when-not (:maps-cached @*state)
                   (future
                     (swap! *state assoc :maps-cached (doall (fs/maps)))))
                 (future
                   (swap! *state assoc :sdp-files-cached (doall (rapid/sdp-files))))
                 (future
                   (swap! *state assoc :rapid-repos-cached (doall (rapid/repos))))
                 (future
                   (swap! *state assoc :engines-cached (doall (fs/engines))))
                 (future
                   (when-let [rapid-repo (:rapid-repo @*state)]
                     (let [versions (->> (rapid/versions rapid-repo)
                                         (sort-by :version version/version-compare)
                                         reverse
                                         doall)]
                       (swap! *state assoc :rapid-versions-cached versions))))
                 (cache-mods))
   :on-advanced (fn [& args]
                  (log/debug "on-advanced" args))
   :on-deleted (fn [& args]
                 (log/debug "on-deleted" args))
   :desc
   {:fx/type fx/ext-many
    :desc
    (concat
      [{:fx/type :stage
        :showing true
        :title "Alt Spring Lobby"
        :width 2000
        :height 1200
        :on-close-request (fn [e]
                            (log/debug e)
                            (when standalone
                              (loop []
                                (let [client (:client @*state)]
                                  (if (and client (not (.isClosed client)))
                                    (do
                                      (client/disconnect client)
                                      (recur))
                                    (System/exit 0))))))
        :scene {:fx/type :scene
                :stylesheets [(str (io/resource "dark-theme2.css"))]
                :root {:fx/type :v-box
                       :alignment :top-left
                       :children [{:fx/type client-buttons
                                   :client client
                                   :client-deferred client-deferred
                                   :username username
                                   :password password
                                   :login-error login-error
                                   :server-url server-url}
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
                                   :battle-title battle-title
                                   :engine-version engine-version
                                   :mod-name mod-name
                                   :map-name map-name
                                   :maps-cached maps-cached
                                   :sdp-files-cached sdp-files-cached
                                   :engines-cached engines-cached
                                   :mods-cached mods-cached}
                                  {:fx/type battle-table
                                   :battles battles
                                   :battle battle
                                   :users users
                                   :username username
                                   :engine-version engine-version
                                   :bot-username bot-username
                                   :bot-name bot-name
                                   :bot-version bot-version
                                   :maps-cached maps-cached
                                   :mods-cached mods-cached}
                                  {:fx/type :v-box
                                   :alignment :center-left
                                   :children
                                   [{:fx/type :label
                                     :text (str last-failed-message)
                                     :style {:-fx-text-fill "#FF0000"}}]}]}}}]
      (when show-rapid-downloader
        (let [sdp-files (or sdp-files-cached [])
              sdp-hashes (set (map rapid/sdp-hash sdp-files))]
          [{:fx/type :stage
            :always-on-top true
            :showing show-rapid-downloader
            :title "alt-spring-lobby Rapid Downloader"
            :on-close-request (fn [& args]
                                (log/debug args)
                                (swap! *state assoc :show-rapid-downloader false))
            :width 1600
            :height 800
            :scene
            {:fx/type :scene
             :stylesheets [(str (io/resource "dark-theme2.css"))]
             :root
             {:fx/type :v-box
              :children
              [{:fx/type :h-box
                :alignment :center-left
                :children
                [{:fx/type :label
                  :text " Repo: "}
                 {:fx/type :choice-box
                  :value (str rapid-repo)
                  :items (or rapid-repos-cached [])
                  :on-value-changed {:event/type ::rapid-repo-change}}
                 {:fx/type :label
                  :text " Engine for pr-downloader: "}
                 {:fx/type :choice-box
                  :value (str engine-version)
                  :items (or engines-cached [])
                  :on-value-changed {:event/type ::version-change}}]}
               {:fx/type :table-view
                :column-resize-policy :constrained ; TODO auto resize
                :items (or rapid-versions-cached [])
                :columns
                [{:fx/type :table-column
                  :text "ID"
                  :cell-value-factory identity
                  :cell-factory
                  {:fx/cell-type :table-cell
                   :describe
                   (fn [i]
                     {:text (str (:id i))})}}
                 {:fx/type :table-column
                  :text "Hash"
                  :cell-value-factory identity
                  :cell-factory
                  {:fx/cell-type :table-cell
                   :describe
                   (fn [i]
                     {:text (str (:hash i))})}}
                 {:fx/type :table-column
                  :text "Version"
                  :cell-value-factory identity
                  :cell-factory
                  {:fx/cell-type :table-cell
                   :describe
                   (fn [i]
                     {:text (str (:version i))})}}
                 {:fx/type :table-column
                  :text "Download"
                  :cell-value-factory identity
                  :cell-factory
                  {:fx/cell-type :table-cell
                   :describe
                   (fn [i]
                     (let [download (get rapid-download (:id i))]
                       (merge
                         {:text (str (:message download))}
                         (cond
                           (sdp-hashes (:hash i))
                           {:graphic
                            {:fx/type font-icon/lifecycle
                             :icon-literal "mdi-check:16:white"}}
                           (:running download)
                           nil
                           :else
                           {:graphic
                            {:fx/type :button
                             :on-action {:event/type ::rapid-download
                                         :rapid-id (:id i)
                                         :engine-version engine-version}
                             :graphic
                             {:fx/type font-icon/lifecycle
                              :icon-literal "mdi-download:16:white"}}}))))}}]}
               {:fx/type :label
                :text " Packages"}
               {:fx/type :table-view
                :column-resize-policy :constrained ; TODO auto resize
                :items sdp-files
                :columns
                [{:fx/type :table-column
                  :text "Filename"
                  :cell-value-factory identity
                  :cell-factory
                  {:fx/cell-type :table-cell
                   :describe
                   (fn [i]
                     {:text (str (.getName i))})}}
                 {:fx/type :table-column
                  :text "ID"
                  :cell-value-factory identity
                  :cell-factory
                  {:fx/cell-type :table-cell
                   :describe
                   (fn [i]
                     {:text (->> i
                                 rapid/sdp-hash
                                 (get rapid/versions-by-hash)
                                 :id
                                 str)})}}
                 {:fx/type :table-column
                  :text "Version"
                  :cell-value-factory identity
                  :cell-factory
                  {:fx/cell-type :table-cell
                   :describe
                   (fn [i]
                     {:text (->> i
                                 rapid/sdp-hash
                                 (get rapid/versions-by-hash)
                                 :version
                                 str)})}}]}]}}}]))
      (when show-http-downloader
        (let [engine-branches ["master" "maintenance" "develop"]] ; TODO
          [{:fx/type :stage
            :always-on-top true
            :showing show-http-downloader
            :title "alt-spring-lobby HTTP Downloader"
            :on-close-request (fn [& args]
                                (log/debug args)
                                (swap! *state assoc :show-http-downloader false))
            :width 1600
            :height 800
            :scene
            {:fx/type :scene
             :stylesheets [(str (io/resource "dark-theme2.css"))]
             :root
             {:fx/type :v-box
              :children
              [{:fx/type :h-box
                :alignment :center-left
                :children
                [{:fx/type :label
                  :text " Engine branch: "}
                 {:fx/type :choice-box
                  :value (str engine-branch)
                  :items (or engine-branches [])
                  :on-value-changed {:event/type ::engine-branch-change}}]}
               {:fx/type :table-view
                :column-resize-policy :constrained ; TODO auto resize
                :items (or (filter :url engine-versions-cached)
                           [])
                :columns
                [{:fx/type :table-column
                  :text "Link"
                  :cell-value-factory identity
                  :cell-factory
                  {:fx/cell-type :table-cell
                   :describe
                   (fn [i]
                     {:text (str (:filename i))})}}
                 {:fx/type :table-column
                  :text "Archive URL"
                  :cell-value-factory identity
                  :cell-factory
                  {:fx/cell-type :table-cell
                   :describe
                   (fn [i]
                     (let [[_all version] (re-find #"(.*)/" (:url i))
                           engine-path (http/engine-path engine-branch version)]
                       {:text (str engine-path)}))}}
                 {:fx/type :table-column
                  :text "Date"
                  :cell-value-factory identity
                  :cell-factory
                  {:fx/cell-type :table-cell
                   :describe
                   (fn [i]
                     {:text (str (:date i))})}}
                 {:fx/type :table-column
                  :text "Size"
                  :cell-value-factory identity
                  :cell-factory
                  {:fx/cell-type :table-cell
                   :describe
                   (fn [i]
                     {:text (str (:size i))})}}
                 {:fx/type :table-column
                  :text "Download"
                  :cell-value-factory identity
                  :cell-factory
                  {:fx/cell-type :table-cell
                   :describe
                   (fn [i]
                     (let [[_all version] (re-find #"(.*)/" (:url i))
                           archive-path (http/engine-path engine-branch version)
                           url (str http/springrts-buildbot-root "/" engine-branch "/" archive-path)
                           download (get http-download url)
                           dest (io/file (fs/spring-root) "engine" (http/engine-archive engine-branch version))]
                       (merge
                         {:text (str (:message download))}
                         (cond
                           (.exists dest)
                           {:graphic
                            {:fx/type font-icon/lifecycle
                             :icon-literal "mdi-check:16:white"}}
                           (:running download)
                           nil
                           :else
                           {:graphic
                            {:fx/type :button
                             :on-action {:event/type ::http-download
                                         :url url
                                         :dest dest}
                             :graphic
                             {:fx/type font-icon/lifecycle
                              :icon-literal "mdi-download:16:white"}}}))))}}]}
               {:fx/type :h-box
                :alignment :center-left
                :children
                [{:fx/type :label
                  :text " Maps index URL: "}
                 {:fx/type :choice-box
                  :value (str maps-index-url)
                  :items [http/springfiles-maps-url
                          (str http/springfightclub-root "/maps")]
                  :on-value-changed {:event/type ::maps-index-change}}]}
               {:fx/type :table-view
                :column-resize-policy :constrained ; TODO auto resize
                :items (or map-files-cache [])
                :columns
                [{:fx/type :table-column
                  :text "Filename"
                  :cell-value-factory identity
                  :cell-factory
                  {:fx/cell-type :table-cell
                   :describe
                   (fn [i]
                     {:text (str (:filename i))})}}
                 {:fx/type :table-column
                  :text "URL"
                  :cell-value-factory identity
                  :cell-factory
                  {:fx/cell-type :table-cell
                   :describe
                   (fn [i]
                     {:text (str (:url i))})}}
                 {:fx/type :table-column
                  :text "Date"
                  :cell-value-factory identity
                  :cell-factory
                  {:fx/cell-type :table-cell
                   :describe
                   (fn [i]
                     {:text (str (:date i))})}}
                 {:fx/type :table-column
                  :text "Size"
                  :cell-value-factory identity
                  :cell-factory
                  {:fx/cell-type :table-cell
                   :describe
                   (fn [i]
                     {:text (str (:size i))})}}
                 {:fx/type :table-column
                  :text "Download"
                  :cell-value-factory identity
                  :cell-factory
                  {:fx/cell-type :table-cell
                   :describe
                   (fn [i]
                     (let [url (str maps-index-url "/" (:url i))
                           download (get http-download url)
                           dest (io/file (fs/spring-root) "maps" (:filename i))]
                       (merge
                         {:text (str (:message download))}
                         (cond
                           (.exists dest)
                           {:graphic
                            {:fx/type font-icon/lifecycle
                             :icon-literal "mdi-check:16:white"}}
                           (:running download)
                           nil
                           :else
                           {:graphic
                            {:fx/type :button
                             :on-action {:event/type ::http-download
                                         :url url
                                         :dest dest}
                             :graphic
                             {:fx/type font-icon/lifecycle
                              :icon-literal "mdi-download:16:white"}}}))))}}]}]}}}])))}})


(defn -main [& _args]
  (Platform/setImplicitExit true)
  (swap! *state assoc :standalone true)
  (watch-config-state)
  (let [r (fx/create-renderer
            :middleware (fx/wrap-map-desc
                          (fn [state]
                            {:fx/type root-view
                             :state state}))
            :opts {:fx.opt/map-event-handler event-handler})]
    (fx/mount-renderer *state r)))
