(ns skylobby.fx.sub
  (:require
    [cljfx.api :as fx]
    [clojure.edn :as edn]
    [clojure.string :as string]
    [skylobby.fs :as fs]
    [skylobby.resource :as resource]
    [skylobby.spring :as spring]
    [skylobby.util :as u]
    [taoensso.timbre :as log]
    [version-clj.core :as version]))


(set! *warn-on-reflection* true)


(defn get-battle-id [state server-key]
  (get-in state [:by-server server-key :battle :battle-id]))

(defn host-username [context server-key]
  (let [battle-id (fx/sub-val context get-battle-id server-key)]
    (fx/sub-val context get-in [:by-server server-key :battles battle-id :host-username])))

(defn am-host [context server-key]
  (let [username (fx/sub-val context get-in [:by-server server-key :username])
        host-username (fx/sub-ctx context host-username server-key)]
    (= username host-username)))

(defn host-ingame [context server-key]
  (let [host-username (fx/sub-ctx context host-username server-key)]
    (fx/sub-val context get-in [:by-server server-key :users host-username :client-status :ingame])))

(defn my-battle-state [context server-key]
  (let [username (fx/sub-val context get-in [:by-server server-key :username])]
    (fx/sub-val context get-in [:by-server server-key :battle :users username])))

(defn my-battle-status [context server-key]
  (let [my-battle-state (fx/sub-ctx context my-battle-state server-key)]
    (:battle-status my-battle-state)))

(defn my-sync-status [context server-key]
  (let [my-battle-status (fx/sub-ctx context my-battle-status server-key)]
    (int
      (if (= :direct server-key)
        (let [battle-id (fx/sub-val context get-battle-id server-key)]
          (if (and (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-version])
                   (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-modname])
                   (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-map]))
            1
            2))
        (or (:sync my-battle-status) 0)))))

(defn my-team-color [context server-key]
  (let [my-battle-state (fx/sub-ctx context my-battle-state server-key)]
    (:team-color my-battle-state)))

(defn am-spec [context server-key]
  (let [my-battle-status (fx/sub-ctx context my-battle-status server-key)]
    (-> my-battle-status :mode not)))

(defn my-client-status [context server-key]
  (let [username (fx/sub-val context get-in [:by-server server-key :username])]
    (fx/sub-val context get-in [:by-server server-key :users username :client-status])))

(defn am-ingame [context server-key]
  (let [my-client-status (fx/sub-ctx context my-client-status server-key)]
    (:ingame my-client-status)))

(defn startpostype [context server-key]
  (let [scripttags (fx/sub-val context get-in [:by-server server-key :battle :scripttags])]
    (spring/startpostype-name (get-in scripttags ["game" "startpostype"]))))

(defn spring-resources [context spring-root]
  (resource/spring-root-resources spring-root (fx/sub-val context :by-spring-root)))

(defn indexed-engine [context spring-root engine-version]
  (let [{:keys [engines-by-version]} (fx/sub-ctx context spring-resources spring-root)]
    (get engines-by-version engine-version)))

(defn indexed-engines [context spring-root engine-version]
  (let [{:keys [engines-grouped-by-version]} (fx/sub-ctx context spring-resources spring-root)]
    (get engines-grouped-by-version engine-version)))

(defn indexed-map [context spring-root map-name]
  (let [{:keys [maps-by-name]} (fx/sub-ctx context spring-resources spring-root)]
    (get maps-by-name map-name)))

(defn indexed-mod [context spring-root mod-name]
  (let [{:keys [mods-by-name mods-by-name-only]} (fx/sub-ctx context spring-resources spring-root)]
    (or (get mods-by-name mod-name)
        (get mods-by-name (u/mod-name-git-no-ref mod-name))
        (->> (get mods-by-name-only
               (when mod-name
                 (let [[_all game-type] (re-find #"(.*)\s+\$VERSION" mod-name)]
                   game-type)))
             (filter (comp #{:directory} :skylobby.fs/source))
             first))))

(defn server-url [context server-key]
   (fx/sub-val context get-in [:by-server server-key :client-data :server-url]))

(defn spring-root [context server-key]
  (or (when (not= :local server-key)
        (let [servers (fx/sub-val context :servers)
              server-url (fx/sub-ctx context server-url server-key)]
          (-> servers (get server-url) :spring-isolation-dir)))
      (fx/sub-val context :spring-isolation-dir)))

(defn games [context spring-root]
  (let [{:keys [mods]} (fx/sub-ctx context spring-resources spring-root)]
    (filter :is-game mods)))

(defn filtered-games [context spring-root]
  (let [
        games (fx/sub-ctx context games spring-root)
        mod-filter (fx/sub-val context :mod-filter)
        filter-lc (if mod-filter (string/lower-case mod-filter) "")]
    (->> games
         (map :mod-name)
         (filter string?)
         (filter #(string/includes? (string/lower-case %) filter-lc))
         (sort version/version-compare))))

(defn parsed-selected-server-tab [context]
  (let [selected-server-tab (fx/sub-val context :selected-server-tab)]
    (or (try
          (edn/read-string selected-server-tab)
          (catch Exception e
            (log/debug e "Error parsing selected server tab edn")))
        selected-server-tab)))

(defn replay-sources [context]
  (let [
        extra-replay-sources (fx/sub-val context :extra-replay-sources)
        servers (fx/sub-val context :servers)
        spring-isolation-dir (fx/sub-val context :spring-isolation-dir)]
    (fs/replay-sources
      {:extra-replay-sources extra-replay-sources
       :servers servers
       :spring-isolation-dir spring-isolation-dir})))
