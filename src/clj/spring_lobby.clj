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
    spring-lobby.client.handler
    [spring-lobby.fs :as fs]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.http :as http]
    [spring-lobby.rapid :as rapid]
    [spring-lobby.spring :as spring]
    [spring-lobby.spring.script :as spring-script]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log]
    [version-clj.core :as version])
  (:import
    (javafx.application Platform)
    (javafx.scene.paint Color)
    (manifold.stream SplicedStream)
    (org.apache.commons.io.input CountingInputStream))
  (:gen-class))


(set! *warn-on-reflection* true)


(defn slurp-app-edn
  "Returns data loaded from a .edn file in this application's root directory."
  [edn-filename]
  (try
    (let [config-file (io/file (fs/app-root) edn-filename)]
      (when (.exists config-file)
        (-> config-file slurp edn/read-string)))
    (catch Exception e
      (log/warn e "Exception loading app edn file" edn-filename))))


(defn initial-state []
  (merge
    {}
    (slurp-app-edn "config.edn")
    (slurp-app-edn "maps.edn")
    (slurp-app-edn "engines.edn")
    (slurp-app-edn "mods.edn")))


(def ^:dynamic *state
  (atom (initial-state)))


(defn spit-app-edn
  "Writes the given data as edn to the given file in the application directory."
  [data filename]
  (let [app-root (io/file (fs/app-root))
        file (io/file app-root filename)]
    (when-not (.exists app-root)
      (.mkdirs app-root))
    (spit file (with-out-str (pprint data)))))


(defn add-watch-state-to-edn
  "Adds a watcher to *state that writes the data, returned by applying filter-fn, when that data
  changes, to output-filename in the app directory."
  [state-atom watcher-kw filter-fn output-filename]
  (add-watch state-atom
    watcher-kw
    (fn [_k _ref old-state new-state]
      (let [old-data (filter-fn old-state)
            new-data (filter-fn new-state)]
        (when (not= old-data new-data)
          (log/debug "Updating" output-filename)
          (spit-app-edn new-data output-filename))))))


(def config-keys
  [:username :password :server-url :engine-version :mod-name :map-name
   :battle-title :battle-password
   :bot-username :bot-name :bot-version
   :engine-branch :maps-index-url :scripttags])

(defn select-config [state]
  (select-keys state config-keys))

(defn select-maps [state]
  (select-keys state [:maps]))

(defn select-engines [state]
  (select-keys state [:engines]))

(defn select-mods [state]
  (select-keys state [:mods]))


(defn add-watchers
  "Adds all *state watchers."
  [state-atom]
  (add-watch-state-to-edn state-atom :config select-config "config.edn")
  (add-watch-state-to-edn state-atom :maps select-maps "maps.edn")
  (add-watch-state-to-edn state-atom :engines select-engines "engines.edn")
  (add-watch-state-to-edn state-atom :mods select-mods "mods.edn"))


(defn reconcile-engines
  "Reads engine details and updates missing engines in :engines in state."
  [state-atom]
  (let [before (u/curr-millis)
        engine-dirs (fs/engine-dirs)
        known-absolute-paths (->> state-atom deref :engines (map :engine-dir-absolute-path) set)
        to-add (remove (comp known-absolute-paths #(.getAbsolutePath ^java.io.File %)) engine-dirs)
        absolute-path-set (set (map #(.getAbsolutePath ^java.io.File %) engine-dirs))
        to-remove (set (remove absolute-path-set known-absolute-paths))]
    (log/info "Found" (count to-add) "engines to load in" (- (u/curr-millis) before) "ms")
    (doseq [engine-dir to-add]
      (log/info "Detecting engine data for" engine-dir)
      (let [engine-data (fs/engine-data engine-dir)]
        (swap! state-atom update :engines
               (fn [engines]
                 (set (conj engines engine-data))))))
    (log/debug "Removing" (count to-remove) "engines")
    (swap! state-atom update :engines
           (fn [engines]
             (set (remove
                    (comp to-remove :engine-dir-absolute-path)
                    engines))))
    {:to-add-count (count to-add)
     :to-remove-count (count to-remove)}))

(defn reconcile-mods
  "Reads mod details and updates missing mods in :mods in state."
  [state-atom]
  (let [before (u/curr-millis)
        mods (->> state-atom deref :mods)
        {:keys [rapid archive]} (group-by ::fs/source mods)
        known-archive-paths (set (map :absolute-path archive))
        known-rapid-paths (set (map :absolute-path rapid))
        mod-archives (fs/mod-files)
        sdp-files (rapid/sdp-files)
        _ (log/info "Found" (count mod-archives) "archives and" (count sdp-files) "rapid archives to scan for mods")
        to-add-archive (remove (comp known-archive-paths #(.getAbsolutePath ^java.io.File %)) mod-archives)
        to-add-rapid (remove (comp known-rapid-paths #(.getAbsolutePath ^java.io.File %)) sdp-files)
        add-mod-fn (fn [mod-data]
                     (swap! state-atom update :mods
                           (fn [mods]
                             (set (conj mods mod-data)))))]
    (log/info "Found" (count to-add-archive) "mod archives and" (count to-add-rapid)
              "rapid files to scan for mods in" (- (u/curr-millis) before) "ms")
    (doseq [archive-file to-add-archive]
      (log/info "Reading mod from" archive-file)
      (add-mod-fn (fs/read-mod-file archive-file)))
    (doseq [sdp-file to-add-rapid]
      (log/info "Reading mod from" sdp-file)
      (add-mod-fn (rapid/read-sdp-mod sdp-file)))
    {:to-add-archive-count (count to-add-archive)
     :to-add-rapid-count (count to-add-rapid)}))

#_
(rapid/read-sdp-mod (io/file "/mnt/c/Users/craig/Documents/My Games/Spring/packages/ea6419652961687d4c31a3b13987e9a5.sdp"))


(def ^java.io.File maps-cache-root
  (io/file (fs/app-root) "maps-cache"))

(defn map-cache-file [map-name]
  (io/file maps-cache-root (str map-name ".edn")))

(defn safe-read-map-cache [map-name]
  (log/info "Reading map cache for" (str "'" map-name "'"))
  (try
    (edn/read-string (slurp (map-cache-file map-name)))
    (catch Exception e
      (log/warn e "Error loading map cache for" (str "'" map-name "'")))))

(defn reconcile-maps
  "Reads map details and caches for maps missing from :maps in state."
  [state-atom]
  (let [before (u/curr-millis)
        map-files (fs/map-files)
        known-filenames (->> state-atom deref :maps (map :filename) set)
        todo (remove (comp known-filenames #(.getName ^java.io.File %)) map-files)]
    (log/info "Found" (count todo) "maps to load in" (- (u/curr-millis) before) "ms")
    (when-not (.exists maps-cache-root)
      (.mkdirs maps-cache-root))
    (doseq [map-file todo]
      (log/info "Reading" map-file)
      (let [{:keys [map-name] :as map-data} (fs/read-map-data map-file)
            map-cache-file (map-cache-file (:map-name map-data))]
        (if map-name
          (do
            (log/info "Caching" map-file "to" map-cache-file)
            (spit map-cache-file (with-out-str (pprint map-data)))
            (swap! state-atom update :maps
                   (fn [maps]
                     (set (conj maps (select-keys map-data [:filename :map-name]))))))
          (log/warn "No map name found for" map-file))))
    (log/debug "Removing maps with no name")
    (swap! state-atom update :maps (fn [maps] (set (filter :map-name maps))))
    {:todo-count (count todo)}))


(defmulti event-handler :event/type)


(defmethod event-handler ::reload-engines [_e]
  (future
    (try
      (reconcile-engines *state)
      (catch Exception e
        (log/error e "Error reloading engines")))))


(defmethod event-handler ::reload-mods [_e]
  (future
    (try
      (reconcile-mods *state)
      (catch Exception e
        (log/error e "Error reloading mods")))))

(defmethod event-handler ::reload-maps [_e]
  (future
    (try
      (reconcile-maps *state)
      (catch Exception e
        (log/error e "Error reloading maps")))))


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
              :icon-literal "mdi-account:16"}})))}}
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



(defn open-battle
  [client {:keys [battle-type nat-type battle-password host-port max-players mod-hash rank map-hash
                  engine engine-version map-name title mod-name]
           :or {battle-type 0
                nat-type 0
                battle-password "*"
                host-port 8452
                max-players 8
                rank 0
                engine "Spring"}}]
  (client/send-message client
    (str "OPENBATTLE " battle-type " " nat-type " " battle-password " " host-port " " max-players
         " " mod-hash " " rank " " map-hash " " engine "\t" engine-version "\t" map-name "\t" title
         "\t" mod-name)))

(defn host-battle []
  (let [{:keys [client scripttags] :as state} @*state]
    (open-battle client
      (-> state
          (clojure.set/rename-keys {:battle-title :title})
          (select-keys [:battle-password :title :engine-version
                        :mod-name :map-name])
          (assoc :mod-hash -1
                 :map-hash -1)))
    (when (seq scripttags)
      (client/send-message client (str "SETSCRIPTTAGS " (spring-script/format-scripttags scripttags))))))

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

(defmethod event-handler ::maps-key-typed [{:fx/keys [^javafx.scene.input.KeyEvent event]}]
  (swap! *state update :map-input-prefix (fn [x] (str x (.getCharacter event))))
  (log/info (-> *state deref :map-input-prefix)))

(defmethod event-handler ::maps-hidden [_e]
  (swap! *state dissoc :map-input-prefix))


(defn map-list
  [{:keys [disable map-name maps on-value-changed]}]
  (cond
    (not maps)
    {:fx/type :v-box
     :alignment :center-left
     :children
     [{:fx/type :label
       :text "Loading maps..."}]}
    (not (seq maps))
    {:fx/type :v-box
     :alignment :center-left
     :children
     [{:fx/type :label
       :text "No maps"}]}
    :else
    {:fx/type :h-box
     :alignment :center-left
     :children
     (concat
       [{:fx/type :label
         :text " Map: "}
        {:fx/type :choice-box
         :value (str map-name)
         :items (->> maps
                     (map :map-name)
                     sort)
         :disable (boolean disable)
         :on-value-changed on-value-changed
         :on-key-typed {:event/type ::maps-key-typed}
         :on-hidden {:event/type ::maps-hidden}}
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
           :icon-literal (str "mdi-download:16:white")}}}]
       (when (seq maps)
         [{:fx/type fx.ext.node/with-tooltip-props
           :props
           {:tooltip
            {:fx/type :tooltip
             :show-delay [10 :ms]
             :text "Random map"}}
           :desc
           {:fx/type :button
            :on-action (assoc on-value-changed :map-name (:map-name (rand-nth (seq maps))))
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
           :icon-literal "mdi-refresh:16:white"}}}])}))

(defn battles-buttons
  [{:keys [battle battles battle-password client selected-battle
           battle-title engine-version mod-name map-name maps engines
           mods map-input-prefix]}]
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
           :items (or (->> engines
                           (map :engine-version)
                           sort)
                      [])
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
             :icon-literal (str "mdi-download:16:white")}}}
          {:fx/type fx.ext.node/with-tooltip-props
           :props
           {:tooltip
            {:fx/type :tooltip
             :show-delay [10 :ms]
             :text "Reload engines"}}
           :desc
           {:fx/type :button
            :on-action {:event/type ::reload-engines}
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal "mdi-refresh:16:white"}}}]}
        {:fx/type :h-box
         :alignment :center-left
         :children
         (concat
           (if mods
             [{:fx/type :label
               :alignment :center-left
               :text " Game: "}
              {:fx/type :choice-box
               :value (str mod-name)
               :items (->> mods
                           (filter :modinfo)
                           (map :modinfo)
                           (map (fn [modinfo]
                                  (str (:name modinfo) " " (:version modinfo))))
                           (sort version/version-compare))
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
               :icon-literal (str "mdi-download:16:white")}}}
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
         :maps maps
         :map-input-prefix map-input-prefix
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
  (let [map-name (or map-name event)]
    (swap! *state assoc
           :map-name map-name
           :map-details (safe-read-map-cache map-name))))

(defmethod event-handler ::battle-map-change
  [{:fx/keys [event] :keys [map-name]}]
  (let [spectator-count 0 ; TODO
        locked 0
        map-hash -1 ; TODO
        map-name (or map-name event)
        m (str "UPDATEBATTLEINFO " spectator-count " " locked " " map-hash " " map-name)]
    (swap! *state assoc
           :map-name map-name
           :map-details (safe-read-map-cache map-name))
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
        (let [nn (inc (u/to-number n))]
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
  [{:keys [bots] :fx/keys [event]}]
  (let [bot-name event
        bot-version (-> (group-by :bot-name bots)
                        (get bot-name)
                        first
                        :bot-version)]
    (swap! *state assoc :bot-name bot-name :bot-version bot-version)))

(defmethod event-handler ::change-bot-version
  [{:fx/keys [event]}]
  (swap! *state assoc :bot-version event))


(defmethod event-handler ::start-battle [_e]
  (spring/start-game @*state))


(def start-pos-r 10.0)
(def map-multiplier 8.0)

(def minimap-size 384)


(defn normalize-team
  "Returns :team1 from either :team1 or :1."
  [team-kw]
  (let [[_all team] (re-find #"(\d+)" (name team-kw))]
    (keyword (str "team" team))))

(defn minimap-starting-points
  [battle-details map-details scripttags minimap-width minimap-height]
  (let [{:keys [map-width map-height]} (-> map-details :smf :header)
        battle-team-keys (spring/team-keys (spring/teams battle-details))
        teams (or (->> map-details :mapinfo :teams
                       (map
                         (fn [[k v]]
                           [k
                            (-> v
                                (update-in [:startpos :x] u/to-number)
                                (update-in [:startpos :z] u/to-number))]))
                       seq)
                  (->> map-details
                       :smd
                       :map
                       (filter (comp #(string/starts-with? % "team") name first))
                       (map
                         (fn [[k {:keys [startposx startposz]}]]
                           [k {:startpos {:x startposx :z startposz}}]))
                       (into {})))
        missing-teams (clojure.set/difference
                        (set (map normalize-team battle-team-keys))
                        (set (map (comp normalize-team first) teams)))
        midx (if map-width (quot (* map-multiplier map-width) 2) 0)
        midz (if map-height (quot (* map-multiplier map-height) 2) 0)
        all-teams (concat teams (map (fn [team] [team {}]) missing-teams))]
    (when (and (number? map-width)
               (number? map-height)
               (number? minimap-width)
               (number? minimap-height))
      (->> all-teams
           (map
             (fn [[team-kw {:keys [startpos]}]]
               (let [{:keys [x z]} startpos
                     [_all team] (re-find #"(\d+)" (name team-kw))
                     normalized (normalize-team team-kw)
                     scriptx (some-> scripttags :game normalized :startposx u/to-number)
                     scriptz (some-> scripttags :game normalized :startposz u/to-number)
                     x (or scriptx x midx)
                     z (or scriptz z midz)]
                 (when (and (number? x) (number? z))
                   {:x (- (* (/ x (* map-multiplier map-width)) minimap-width)
                          (/ start-pos-r 2))
                    :y (- (* (/ z (* map-multiplier map-height)) minimap-height)
                          (/ start-pos-r 2))
                    :team team}))))
           (filter some?)))))

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


(defmethod event-handler ::minimap-mouse-pressed
  [{:fx/keys [^javafx.scene.input.MouseEvent event] :keys [starting-points startpostype]}]
  (when (= "Choose before game" startpostype)
    (let [ex (.getX event)
          ey (.getY event)]
      (when-let [target (some
                          (fn [{:keys [x y] :as target}]
                            (when (and
                                    (< x ex (+ x (* 2 start-pos-r)))
                                    (< y ey (+ y (* 2 start-pos-r))))
                              target))
                          starting-points)]
        (swap! *state assoc :drag-team {:team (:team target)
                                        :x (- ex start-pos-r)
                                        :y (- ey start-pos-r)})))))


(defmethod event-handler ::minimap-mouse-dragged
  [{:fx/keys [^javafx.scene.input.MouseEvent event]}]
  (swap! *state
         (fn [state]
           (if (:drag-team state)
             (update state :drag-team assoc
                     :x (- (.getX event) start-pos-r)
                     :y (- (.getY event) start-pos-r))
             state))))

(defmethod event-handler ::minimap-mouse-released
  [{:keys [minimap-width minimap-height map-details]}]
  (when-let [{:keys [team x y]} (-> *state deref :drag-team)]
    (let [{:keys [map-width map-height]} (-> map-details :smf :header)
          x (* (/ x minimap-width) map-width map-multiplier)
          z (* (/ y minimap-height) map-height map-multiplier)
          scripttags {:game
                      {(keyword (str "team" team))
                       {:startposx x
                        :startposz z}}}]
      (log/debug scripttags)
      (swap! *state update :scripttags u/deep-merge scripttags)
      (swap! *state update-in [:battle :scripttags] u/deep-merge scripttags)
      (client/send-message (:client @*state) (str "SETSCRIPTTAGS " (spring-script/format-scripttags scripttags)))))
  (swap! *state dissoc :drag-team))


(defn minimap-dimensions [map-smf-header]
  (let [{:keys [map-width map-height]} map-smf-header]
    (when (and map-width)
      (let [ratio-x (/ minimap-size map-width)
            ratio-y (/ minimap-size map-height)
            min-ratio (min ratio-x ratio-y)
            normal-x (/ ratio-x min-ratio)
            normal-y (/ ratio-y min-ratio)
            invert-x (/ min-ratio ratio-x)
            invert-y (/ min-ratio ratio-y)
            convert-x (if (< ratio-y ratio-x) invert-x normal-x)
            convert-y (if (< ratio-x ratio-y) invert-y normal-y)
            minimap-width (* minimap-size convert-x)
            minimap-height (* minimap-size convert-y)]
        {:minimap-width minimap-width
         :minimap-height minimap-height}))))


(defn battle-buttons
  [{:keys [am-host host-user host-username maps battle-map bot-username bot-name bot-version engines
           engine-version map-details battle battles username users mods drag-team
           map-input-prefix]}]
  (let [battle-modname (:battle-modname (get battles (:battle-id battle)))
        mod-details (some->> mods
                             (filter (comp #{battle-modname}
                                           (fn [modinfo]
                                             (str (:name modinfo) " " (:version modinfo)))
                                           :modinfo))
                             first)
        scripttags (:scripttags battle)
        startpostype (->> scripttags
                          :game
                          :startpostype
                          spring/startpostype-name)
        {:keys [minimap-width minimap-height] :or {minimap-width minimap-size minimap-height minimap-size}} (minimap-dimensions (-> map-details :smf :header))
        battle-details (spring/battle-details {:battle battle :battles battles :users users})
        starting-points (minimap-starting-points battle-details map-details scripttags minimap-width minimap-height)
        engine-dir-filename (spring/engine-dir-filename engines engine-version)
        bots (fs/bots engine-dir-filename)]
    {:fx/type :h-box
     :children
     (concat
       [{:fx/type :v-box
         :children
         [{:fx/type map-list
           :disable (not am-host)
           :map-name battle-map
           :maps maps
           :map-input-prefix map-input-prefix
           :on-value-changed {:event/type ::battle-map-change}}
          {:fx/type :pane
           :v-box/vgrow :always}
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
                                :bots bots}
             :items (map :bot-name bots)}
            {:fx/type :choice-box
             :value (str bot-version)
             :on-value-changed {:event/type ::change-bot-version}
             :items (map :bot-version
                         (or (get (group-by :bot-name bots)
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
           (concat
             [{:fx/type :label
               :alignment :center-left
               :text "Start Positions: "}
              {:fx/type :choice-box
               :value startpostype
               :items (map str (vals spring/startpostypes))
               :disable (not am-host)
               :on-value-changed {:event/type ::battle-startpostype-change}}]
             (when (= "Choose before game" startpostype)
               [{:fx/type :button
                 :text "Reset"
                 :on-action {:event/type ::reset-start-positions}}]))}
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
             :text " Ready"}
            {:fx/type :pane
             :h-box/hgrow :always}
            {:fx/type fx.ext.node/with-tooltip-props
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
              :on-action {:event/type ::start-battle}}}]}]}
        {:fx/type :pane
         :h-box/hgrow :always}
        {:fx/type :v-box
         :alignment :top-left
         :h-box/hgrow :always
         :children
         [{:fx/type :label
           :text "modoptions"}
          {:fx/type :table-view
           :column-resize-policy :constrained
           :items (or (some->> mod-details
                               :modoptions
                               (map second)
                               (filter :key)
                               (map #(update % :key (comp keyword string/lower-case)))
                               (sort-by :key)
                               (remove (comp #{"section"} :type)))
                      [])
           :columns
           [{:fx/type :table-column
             :text "Key"
             :cell-value-factory identity
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [i]
                {:text ""
                 :graphic
                 {:fx/type fx.ext.node/with-tooltip-props
                  :props
                  {:tooltip
                   {:fx/type :tooltip
                    :show-delay [10 :ms]
                    :text (str (:name i) "\n\n" (:desc i))}}
                  :desc
                  (merge
                    {:fx/type :label
                     :text (or (some-> i :key name str)
                               "")}
                    (when-let [v (-> battle :scripttags :game :modoptions (get (:key i)))]
                      (when (and (not= (:def i) v)
                                 (not= (u/to-number (:def i))
                                       (u/to-number v)))
                        {:style {:-fx-font-weight :bold}})))}})}}
            #_
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
                (case (:type i)
                  "bool"
                  {:text ""
                   :graphic
                   {:fx/type ext-recreate-on-key-changed
                    :key (str (:key i))
                    :desc
                    {:fx/type fx.ext.node/with-tooltip-props
                     :props
                     {:tooltip
                      {:fx/type :tooltip
                       :show-delay [10 :ms]
                       :text (str (:name i) "\n\n" (:desc i))}}
                     :desc
                     {:fx/type :check-box
                      :selected (boolean (edn/read-string (or (-> battle :scripttags :game :modoptions (get (:key i)))
                                                              (:def i))))
                      :on-selected-changed {:event/type ::modoption-change
                                            :modoption-key (:key i)}
                      :disable (not am-host)}}}}
                  "number"
                  {:text ""
                   :graphic
                   {:fx/type ext-recreate-on-key-changed
                    :key (str (:key i))
                    :desc
                    {:fx/type fx.ext.node/with-tooltip-props
                     :props
                     {:tooltip
                      {:fx/type :tooltip
                       :show-delay [10 :ms]
                       :text (str (:name i) "\n\n" (:desc i))}}
                     :desc
                     {:fx/type :text-field
                      :disable (not am-host)
                      :text-formatter
                      {:fx/type :text-formatter
                       :value-converter :number
                       :value (u/to-number (or (-> battle :scripttags :game :modoptions (get (:key i)))
                                               (:def i)))
                       :on-value-changed {:event/type ::modoption-change
                                          :modoption-key (:key i)}}}}}}
                  "list"
                  {:text ""
                   :graphic
                   {:fx/type ext-recreate-on-key-changed
                    :key (str (:key i))
                    :desc
                    {:fx/type fx.ext.node/with-tooltip-props
                     :props
                     {:tooltip
                      {:fx/type :tooltip
                       :show-delay [10 :ms]
                       :text (str (:name i) "\n\n" (:desc i))}}
                     :desc
                     {:fx/type :choice-box
                      :disable (not am-host)
                      :value (or (-> battle :scripttags :game :modoptions (get (:key i)))
                                 (:def i))
                      :on-value-changed {:event/type ::modoption-change
                                         :modoption-key (:key i)}
                      :items (or (map (comp :key second) (:items i))
                                 [])}}}}
                  {:text (str (:def i))}))}}]}]}
        {:fx/type :v-box
         :alignment :top-left
         :children
         [{:fx/type :label
           :text "script.txt preview"}
          {:fx/type :text-area
           :editable false
           :text (str (string/replace (spring/battle-script-txt @*state) #"\t" "  "))
           :style {:-fx-font-family "monospace"}
           :v-box/vgrow :always}]}
        #_
        {:fx/type :v-box
         :alignment :top-left
         :children
         [{:fx/type :label
           :text "map details"}
          {:fx/type :text-area
           :editable false
           :text (with-out-str
                   (pprint map-details))
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
                  :fit-height minimap-height
                  :preserve-ratio true}
                 (merge
                   (when am-host
                     {:on-mouse-pressed {:event/type ::minimap-mouse-pressed
                                         :startpostype startpostype
                                         :starting-points starting-points}
                      :on-mouse-dragged {:event/type ::minimap-mouse-dragged
                                         :startpostype startpostype
                                         :starting-points starting-points}
                      :on-mouse-released {:event/type ::minimap-mouse-released
                                          :startpostype startpostype
                                          :map-details map-details
                                          :minimap-width minimap-width
                                          :minimap-height minimap-height}})
                   {:fx/type :canvas
                    :width minimap-width
                    :height minimap-height
                    :draw
                    (fn [^javafx.scene.canvas.Canvas canvas]
                      (let [gc (.getGraphicsContext2D canvas)]
                        (.clearRect gc 0 0 minimap-width minimap-height)
                        (.setFill gc Color/RED)
                        (doseq [{:keys [x y team]} starting-points]
                          (let [drag (when (and drag-team
                                                (= team (:team drag-team)))
                                       drag-team)
                                x (or (:x drag) x)
                                y (or (:y drag) y)
                                xc (+ x (/ start-pos-r 2.0))
                                yc (+ y (/ start-pos-r 0.75))]
                            (cond
                              (#{"Fixed" "Choose before game"} startpostype)
                              (do
                                (.beginPath gc)
                                (.rect gc x y
                                       (* 2 start-pos-r)
                                       (* 2 start-pos-r))
                                (.setFill gc Color/WHITE)
                                (.fill gc)
                                (.setStroke gc Color/RED)
                                (.stroke gc)
                                (.closePath gc)
                                (.setFill gc Color/RED)
                                (.fillText gc team xc yc))
                              :else
                              (.fillOval gc x y start-pos-r start-pos-r))))))})])}]}])))}))


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


(defn nickname [{:keys [bot-name owner username]}]
  (if bot-name
    (str bot-name (when owner (str " (" owner ")")))
    (str username)))


(defn battle-table
  [{:keys [battle battles map-details users username] :as state}]
  (let [{:keys [host-username battle-map]} (get battles (:battle-id battle))
        host-user (get users host-username)
        am-host (= username host-username)]
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
            (select-keys state [:battle :username :host-username :maps :engines
                                :bot-username :bot-name :bot-version :engine-version
                                :mods :battles :drag-team :map-input-prefix]))]))}))


(defmethod event-handler ::battle-startpostype-change
  [{:fx/keys [event]}]
  (let [startpostype (get spring/startpostypes-by-name event)]
    (swap! *state assoc-in [:scripttags :game :startpostype] startpostype)
    (swap! *state assoc-in [:battle :scripttags :game :startpostype] startpostype)
    (client/send-message (:client @*state) (str "SETSCRIPTTAGS game/startpostype=" startpostype))))

(defmethod event-handler ::reset-start-positions
  [_e]
  (let [team-ids (take 16 (iterate inc 0))
        scripttag-keys (map (fn [i] (str "game/team" i)) team-ids)]
    (doseq [i team-ids]
      (let [team (keyword (str "team" i))]
        (swap! *state update-in [:scripttags :game] dissoc team)
        (swap! *state update-in [:battle :scripttags :game] dissoc team)))
    (client/send-message (:client @*state) (str "REMOVESCRIPTTAGS " (string/join " " scripttag-keys)))))

(defmethod event-handler ::modoption-change
  [{:keys [modoption-key] :fx/keys [event]}]
  (let [value (str event)]
    (swap! *state assoc-in [:scripttags :game :modoptions modoption-key] (str event))
    (swap! *state assoc-in [:battle :scripttags :game :modoptions modoption-key] (str event))
    (client/send-message (:client @*state) (str "SETSCRIPTTAGS game/modoptions/" (name modoption-key) "=" value))))

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
  (let [^javafx.scene.control.ComboBoxBase source (.getSource event)
        javafx-color (.getValue source)
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
  [{:keys [engine-dir-filename rapid-id]}]
  (swap! *state assoc-in [:rapid-download rapid-id] {:running true
                                                     :message "Preparing to run pr-downloader"})
  (future
    (try
      (let [pr-downloader-file (io/file (fs/spring-root) "engine" engine-dir-filename (fs/executable "pr-downloader"))
            command [(.getAbsolutePath pr-downloader-file)
                     "--filesystem-writepath" (fs/wslpath (fs/spring-root))
                     "--rapid-download" rapid-id]
            runtime (Runtime/getRuntime)]
        (log/info "Running '" command "'")
        (let [^"[Ljava.lang.String;" cmdarray (into-array String command)
              ^"[Ljava.lang.String;" envp nil
              ^java.lang.Process process (.exec runtime cmdarray envp (fs/spring-root))]
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
          (swap! *state assoc :sdp-files-cached (doall (rapid/sdp-files)))))
      (catch Exception e
        (log/error e "Error downloading" rapid-id)
        (swap! *state assoc-in [:rapid-download rapid-id :message] (.getMessage e))
        (swap! *state assoc-in [:rapid-download rapid-id :running] false)))))

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
  [v idx value]
  (-> (subvec v 0 idx)
      (conj value)
      (into (subvec v idx))))

(defn insert-after
  "Finds an item into a vector and adds val just after it.
   If needle is not found, the input vector will be returned."
  [^clojure.lang.APersistentVector v needle value]
  (let [index (.indexOf v needle)]
    (if (neg? index)
      v
      (insert-at v (inc index) value))))

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
              ^int content-length (get-in request [:headers "content-length"] 0)
              length (Integer/valueOf content-length)
              buffer-size (* 1024 10)]
          (with-open [^java.io.InputStream input (:body request)
                      output (io/output-stream dest)]
            (let [buffer (make-array Byte/TYPE buffer-size)
                  ^CountingInputStream counter (:downloaded-bytes-counter request)]
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
  [{{:keys [users battles
            engine-version last-failed-message
            standalone
            rapid-repo rapid-download sdp-files-cached rapid-repos-cached engines rapid-versions-by-hash
            rapid-versions-cached
            show-rapid-downloader
            engine-branch engine-versions-cached http-download
            maps-index-url map-files-cache
            show-http-downloader]
     :as state}
    :state}]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created (fn [& args]
                 (log/trace "on-created" args)
                 (future
                   (try
                     (reconcile-engines *state)
                     (catch Exception e
                       (log/error e "Error reconciling engines"))))
                 (future
                   (try
                     (reconcile-mods *state)
                     (catch Exception e
                       (log/error e "Error reconciling mods"))))
                 (future
                   (try
                     (reconcile-maps *state)
                     (when-let [map-name (:map-name @*state)]
                       (swap! *state assoc :map-details (safe-read-map-cache map-name)))
                     (catch Exception e
                       (log/error e "Error reconciling maps"))))
                 (future
                   (try
                     (swap! *state assoc :sdp-files-cached (doall (rapid/sdp-files)))
                     (catch Exception e
                       (log/error e "Error loading SDP files"))))
                 (future
                   (try
                     (let [rapid-repos (doall (rapid/repos))]
                       (swap! *state assoc :rapid-repos-cached rapid-repos)
                       (swap! *state assoc :rapid-versions-by-hash
                              (->> rapid-repos
                                   (mapcat rapid/versions)
                                   (map (juxt :hash identity))
                                   (into {}))))
                     (catch Exception e
                       (log/error e "Error loading rapid versions by hash"))))
                 (future
                   (try
                     (when-let [rapid-repo (:rapid-repo @*state)]
                       (let [versions (->> (rapid/versions rapid-repo)
                                           (sort-by :version version/version-compare)
                                           reverse
                                           doall)]
                         (swap! *state assoc :rapid-versions-cached versions)))
                     (catch Exception e
                       (log/error e "Error loading rapid versions")))))
   :on-advanced (fn [& args]
                  (log/trace "on-advanced" args))
   :on-deleted (fn [& args]
                 (log/trace "on-deleted" args))
   :desc
   {:fx/type fx/ext-many
    :desc
    (concat
      [{:fx/type :stage
        :showing true
        :title "Alt Spring Lobby"
        :width 1800
        :height 1000
        :on-close-request (fn [e]
                            (log/debug e)
                            (when standalone
                              (loop []
                                (let [^SplicedStream client (:client @*state)]
                                  (if (and client (not (.isClosed client)))
                                    (do
                                      (client/disconnect client)
                                      (recur))
                                    (System/exit 0))))))
        :scene {:fx/type :scene
                :stylesheets [(str (io/resource "dark-theme2.css"))]
                :root {:fx/type :v-box
                       :alignment :top-left
                       :children [(merge
                                    {:fx/type client-buttons}
                                    (select-keys state
                                      [:client :client-deferred :username :password :login-error
                                       :server-url]))
                                  {:fx/type user-table
                                   :users users}
                                  {:fx/type battles-table
                                   :battles battles
                                   :users users}
                                  (merge
                                    {:fx/type battles-buttons}
                                    (select-keys state
                                      [:battle :battle-password :battles :client :selected-battle
                                       :battle-title :engine-version :mod-name :map-name
                                       :maps :map-input-prefix :sdp-files-cached
                                       :engines :mods]))
                                  (merge
                                    {:fx/type battle-table}
                                    (select-keys state
                                      [:battles :battle :users :username :engine-version
                                       :bot-username :bot-name :bot-version :maps :engines
                                       :map-input-prefix :mods :drag-team :map-details]))
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
                  :items (or (->> engines
                                  (map :engine-version)
                                  sort)
                             [])
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
                                         :engine-dir-filename (spring/engine-dir-filename engines engine-version)}
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
                   (fn [^java.io.File i]
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
                                 (get rapid-versions-by-hash)
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
                                 (get rapid-versions-by-hash)
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
  (add-watchers *state)
  (let [r (fx/create-renderer
            :middleware (fx/wrap-map-desc
                          (fn [state]
                            {:fx/type root-view
                             :state state}))
            :opts {:fx.opt/map-event-handler event-handler})]
    (fx/mount-renderer *state r)))
