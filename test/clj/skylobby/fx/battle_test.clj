(ns skylobby.fx.battle-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.battle :as fx.battle]))


(set! *warn-on-reflection* true)


(deftest sync-button
  (is (map?
        (fx.battle/sync-button
          {:fx/context (fx/create-context nil)}))))


(deftest spring-debug-window
  (is (map?
        (fx.battle/spring-debug-window
          {:fx/context (fx/create-context nil)
           :screen-bounds {}}))))


(deftest battle-buttons
  (is (map?
        (fx.battle/battle-buttons
          {:fx/context (fx/create-context nil)})))
  (is (map?
        (fx.battle/battle-buttons
          {:fx/context (fx/create-context nil)
           :server-key :local}))))


(deftest battle-tabs
  (is (map?
        (fx.battle/battle-tabs
          {:fx/context (fx/create-context nil)}))))


(deftest battle-votes-impl
  (is (map?
        (fx.battle/battle-votes-impl
          {:fx/context (fx/create-context nil)})))
  (is (map?
        (fx.battle/battle-votes-impl
          {:fx/context (fx/create-context {:show-vote-log true})})))
  (is (map?
        (fx.battle/battle-votes-impl
          {:fx/context (fx/create-context {:show-vote-log true
                                           :by-server {"server" {:battle {:battle-id "1"}
                                                                 :battles {"1" {:channel-name "channel"
                                                                                :host-username "host"}}
                                                                 :channels {"channel" {:messages [{:username "host"
                                                                                                   :message-type :ex
                                                                                                   :spads {:spads-message-type :called-vote
                                                                                                           :spads-parsed ["" "user" "vote"]}}]}}}}})
           :server-key "server"}))))


(deftest battle-view
  (is (map?
        (fx.battle/battle-view
          {:fx/context (fx/create-context nil)}))))
