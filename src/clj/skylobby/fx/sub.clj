(ns skylobby.fx.sub
  (:require
    [cljfx.api :as fx]
    [skylobby.resource :as resource]
    [spring-lobby.spring :as spring]
    [spring-lobby.util :as u]))


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

(defn indexed-map [context spring-root map-name]
  (let [{:keys [maps-by-name]} (fx/sub-ctx context spring-resources spring-root)]
    (get maps-by-name map-name)))

(defn indexed-mod [context spring-root mod-name]
  (let [{:keys [mods-by-name]} (fx/sub-ctx context spring-resources spring-root)]
    (or (get mods-by-name mod-name)
        (get mods-by-name (u/mod-name-git-no-ref mod-name)))))

(defn server-url [context server-key]
   (fx/sub-val context get-in [:by-server server-key :client-data :server-url]))

(defn spring-root [context server-key]
  (or (when (not= :local server-key)
        (let [servers (fx/sub-val context :servers)
              server-url (fx/sub-ctx context server-url server-key)]
          (-> servers (get server-url) :spring-isolation-dir)))
      (fx/sub-val context :spring-isolation-dir)))
