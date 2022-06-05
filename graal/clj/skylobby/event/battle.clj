(ns skylobby.event.battle
  (:require
    [clojure.string :as string]
    [skylobby.fs :as fs]
    [skylobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (me.xdrop.fuzzywuzzy FuzzySearch)))


(set! *warn-on-reflection* true)


(defn update-player-or-bot-state
  [state-atom server-key {:keys [bot-name username] :as id} state-change]
  (let [is-bot (boolean bot-name)
        battle-kw (if is-bot :bots :users)
        message-type (if is-bot :skylobby.direct/battle-bots :skylobby.direct/battle-users)]
    (log/info "Updating" battle-kw "battle state for" id "with" state-change)
    (case (u/server-type server-key)
      :direct-host
      (let [state (swap! state-atom update-in [:by-server server-key :battle battle-kw (or bot-name username)] u/deep-merge state-change)]
        (if-let [broadcast-fn (get-in state [:by-server server-key :server :broadcast-fn])]
          (let [users-or-bots (get-in state [:by-server server-key :battle battle-kw])]
            (broadcast-fn [message-type users-or-bots]))
          (log/warn "No broadcast-fn" server-key)))
      :direct-client
      (if-let [send-fn (get-in @state-atom [:by-server server-key :client :send-fn])]
        (send-fn [(if is-bot
                    :skylobby.direct.client/bot-state
                    :skylobby.direct.client/player-state)
                  (assoc state-change :bot-name bot-name)])
        (log/warn "No send-fn" server-key)))))

(defn engine-changed
  [state-atom
   {:fx/keys [event] :keys [battle-id engine-version server-key spring-root]}]
  (let [engine-version (or engine-version event)
        {:keys [by-server direct-connect-engine]}
        (swap! state-atom
          (fn [state]
            (let [
                  spring-root (or spring-root (:spring-isolation-dir state))
                  by-spring-root (get-in state [:by-spring-root (fs/canonical-path spring-root)])
                  engine-version-lc (string/lower-case engine-version)
                  engine-versions (->> by-spring-root
                                       :engines
                                       (map :engine-version)
                                       (filter some?)
                                       sort)
                  exact-engine (->> engine-versions
                                    (filter (comp #{engine-version-lc} string/lower-case))
                                    last)
                  starts-with-engine (->> engine-versions
                                          (filter #(string/starts-with? (string/lower-case %) engine-version-lc))
                                          last)
                  includes-engine (->> engine-versions
                                       (filter #(string/includes? (string/lower-case %) engine-version-lc))
                                       last)
                  closest-engine (->> engine-versions
                                      (sort-by
                                        (fn [v]
                                          [(FuzzySearch/ratio v engine-version) v]))
                                      last)
                  engine-version (or exact-engine
                                     starts-with-engine
                                     includes-engine
                                     closest-engine
                                     engine-version)]
              (log/info "Matching engine"
                {:lower-case engine-version-lc
                 :exact exact-engine
                 :starts-with starts-with-engine
                 :includes includes-engine
                 :closest closest-engine})
              (-> state
                  (assoc :engine-filter "")
                  (assoc-in [:by-spring-root (fs/canonical-path spring-root) :engine-version] engine-version)
                  (assoc :direct-connect-engine engine-version)
                  (assoc-in [:by-server server-key :battles battle-id :battle-version] engine-version)))))
        server (get-in by-server [server-key :server])]
    (when (= :direct-host (u/server-type server-key))
      (if-let [broadcast-fn (:broadcast-fn server)]
        (broadcast-fn [:skylobby.direct/battle-details {:battle-version direct-connect-engine}])
        (log/warn "No broadcast-fn found for server" server)))))

(defn map-changed
  [state-atom
   {:fx/keys [event] :keys [battle-id map-name server-key spring-root]}]
  (let [map-name (or map-name event)
        {:keys [by-server direct-connect-map]}
        (swap! state-atom
          (fn [state]
            (let [
                  spring-root (or spring-root (:spring-isolation-dir state))
                  by-spring-root (get-in state [:by-spring-root (fs/canonical-path spring-root)])
                  map-name-lc (string/lower-case map-name)
                  map-names (->> by-spring-root
                                 :maps
                                 (map :map-name)
                                 (filter some?)
                                 sort)
                  exact-map (->> map-names
                                 (filter (comp #{map-name-lc} string/lower-case))
                                 first)
                  starts-with-map (->> map-names
                                       (filter #(string/starts-with? (string/lower-case %) map-name-lc))
                                       first)
                  includes-map (->> map-names
                                    (filter #(string/includes? (string/lower-case %) map-name-lc))
                                    first)
                  closest-map (->> map-names
                                   (sort-by
                                     (fn [v]
                                       [(FuzzySearch/ratio v map-name) v]))
                                   last)
                  map-name (or exact-map
                               starts-with-map
                               includes-map
                               closest-map
                               map-name)]
              (log/info "Matching map"
                {:lower-case map-name-lc
                 :exact exact-map
                 :starts-with starts-with-map
                 :includes includes-map
                 :closest closest-map})
              (-> state
                  (assoc :map-input-prefix "")
                  (assoc-in [:by-spring-root (fs/canonical-path spring-root) :map-name] map-name)
                  (assoc :direct-connect-map map-name)
                  (assoc-in [:by-server server-key :battles battle-id :battle-map] map-name)))))
        server (get-in by-server [server-key :server])]
    (when (= :direct-host (u/server-type server-key))
      (if-let [broadcast-fn (get-in by-server [server-key :server :broadcast-fn])]
        (broadcast-fn [:skylobby.direct/battle-details {:battle-map direct-connect-map}])
        (log/warn "No broadcast-fn found for server" server)))))

(defn mod-changed
  [state-atom
   {:fx/keys [event] :keys [battle-id mod-name server-key spring-root]}]
  (let [mod-name (or mod-name event)
        {:keys [by-server direct-connect-mod]}
        (swap! state-atom
          (fn [state]
            (let [
                  spring-root (or spring-root (:spring-isolation-dir state))
                  by-spring-root (get-in state [:by-spring-root (fs/canonical-path spring-root)])
                  mod-name-lc (string/lower-case mod-name)
                  mod-names (->> by-spring-root
                                 :mods
                                 (filter :is-game)
                                 (map :mod-name)
                                 (filter some?)
                                 sort)
                  exact-mod (->> mod-names
                                 (filter (comp #{mod-name-lc} string/lower-case))
                                 first)
                  starts-with-mod (->> mod-names
                                       (filter #(string/starts-with? (string/lower-case %) mod-name-lc))
                                       last)
                  includes-mod (->> mod-names
                                    (filter #(string/includes? (string/lower-case %) mod-name-lc))
                                    last)
                  closest-mod (->> mod-names
                                   (sort-by
                                     (fn [v]
                                       [(FuzzySearch/ratio v mod-name) v]))
                                   last)
                  mod-name (or exact-mod
                               starts-with-mod
                               includes-mod
                               closest-mod
                               mod-name)]
              (log/info "Matching mod"
                {:lower-case mod-name-lc
                 :exact exact-mod
                 :starts-with starts-with-mod
                 :includes includes-mod
                 :closest closest-mod})
              (-> state
                  (assoc :mod-filter "")
                  (assoc-in [:by-spring-root (fs/canonical-path spring-root) :mod-name] mod-name)
                  (assoc :direct-connect-mod mod-name)
                  (assoc-in [:by-server server-key :battles battle-id :battle-modname] mod-name)))))
        server (get-in by-server [server-key :server])]
    (when (= :direct-host (u/server-type server-key))
      (if-let [broadcast-fn (:broadcast-fn server)]
        (broadcast-fn [:skylobby.direct/battle-details {:battle-modname direct-connect-mod}])
        (log/warn "No broadcast-fn found for server" server)))))


(defn split-boxes [state-atom {:keys [split-percent split-type server-key]}]
  (let [server-type (u/server-type server-key)
        low (with-precision 9 (/ split-percent 100))
        high (- 1.0 low)
        nw {:startrectleft 0
            :startrecttop 0
            :startrectright low
            :startrectbottom low}
        sw {:startrectleft 0
            :startrecttop high
            :startrectright low
            :startrectbottom 1}
        ne {:startrectleft high
            :startrecttop 0
            :startrectright 1
            :startrectbottom low}
        se {:startrectleft high
            :startrecttop high
            :startrectright 1
            :startrectbottom 1}
        allyteams (case split-type
                    "v"
                    {"allyteam0" {:startrectleft 0
                                  :startrecttop 0
                                  :startrectright low
                                  :startrectbottom 1}
                     "allyteam1" {:startrectleft high
                                  :startrecttop 0
                                  :startrectright 1
                                  :startrectbottom 1}}
                    "h"
                    {"allyteam0" {:startrectleft 0
                                  :startrecttop 0
                                  :startrectright 1
                                  :startrectbottom low}
                     "allyteam1" {:startrectleft 0
                                  :startrecttop high
                                  :startrectright 1
                                  :startrectbottom 1}}
                    "c"
                    {"allyteam0" nw
                     "allyteam1" se
                     "allyteam2" sw
                     "allyteam3" ne}
                    "c1"
                    {"allyteam0" nw
                     "allyteam1" se}
                    "c2"
                    {"allyteam0" sw
                     "allyteam1" ne})
        state (swap! state-atom update-in [:by-server server-key :battle :scripttags "game"]
                (fn [game]
                  (->> game
                       (remove (fn [[k _]]
                                 (string/starts-with? (name k) "allyteam")))
                       (into {})
                       (merge allyteams))))]
    (when (#{:direct-host} server-type)
      (if-let [broadcast-fn (get-in state [:by-server server-key :server :broadcast-fn])]
        (let [scripttags (get-in state [:by-server server-key :battle :scripttags])]
          (broadcast-fn [:skylobby.direct/battle-scripttags scripttags]))
        (log/warn "No broadcast-fn" server-key)))))
