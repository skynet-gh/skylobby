(ns skylobby.event-stub)


(defn connect [state-atom {:keys [no-focus server server-key password username] :as state}]
  nil)

(defn disconnect [state-atom server-key]
  nil)

(defn leave-battle [state-atom {:keys [client-data server-key]}]
  nil)

(defn join-battle [state-atom {:keys [battle battle-password battle-passworded client-data selected-battle] :as opts}]
  nil)

(defn set-ignore
  ([state-atom server-key username ignore]
   (set-ignore state-atom server-key username ignore nil))
  ([state-atom server-key username ignore {:keys [channel-name]}]
   nil))

(defn send-message [state-atom {:keys [channel-name client-data message server-key] :as e}]
  nil)

(defn start-battle
  [state-atom {:keys [am-host am-spec battle-status channel-name client-data host-ingame] :as state}]
  nil)

(defn set-my-battle-status
  [state-atom client-data battle-status team-color]
  nil)

(defn set-battle-mode
  [state-atom {:keys [battle-status client-data mode ready-on-unspec team-color]}]
  nil)

(defn set-auto-unspec [state-atom {:keys [auto-unspec server-key] :as opts}]
  nil)

(defn set-battle-ready
  [state-atom {:keys [battle-status client-data ready team-color]}]
  nil)

(defn set-client-status [state-atom {:keys [client-data client-status]}]
  nil)
