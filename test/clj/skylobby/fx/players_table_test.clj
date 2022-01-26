(ns skylobby.fx.players-table-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.players-table :as fx.players-table]))


(set! *warn-on-reflection* true)


(deftest ai-options-window
  (is (map?
        (fx.players-table/ai-options-window
          {:fx/context (fx/create-context {})}))))

(deftest player-context-menu
  (is (map?
        (fx.players-table/player-context-menu
          {:fx/context (fx/create-context {})}))))

(deftest player-status-tooltip-label
  (is (map?
        (fx.players-table/player-status-tooltip-label
          {:fx/context (fx/create-context {})}))))

(deftest player-status
  (is (map?
        (fx.players-table/player-status
          {:fx/context (fx/create-context {})}))))

(deftest player-name-tooltip
  (is (map?
        (fx.players-table/player-name-tooltip
          {:fx/context (fx/create-context {})}))))


(def fake-players
  [{}
   {:battle-status {:mode true
                    :ally 0
                    :id 0}}])

(deftest players-table
  (is (map?
        (fx.players-table/players-table-impl
          {:fx/context
           (fx/create-context
             {
              :players-table-columns {:skill true
                                      :ally true
                                      :team true
                                      :color true
                                      :status true
                                      :spectator true
                                      :faction true
                                      :country true
                                      :bonus true}})
           :players fake-players}))))


(deftest players-not-a-table
  (is (map?
        (fx.players-table/players-not-a-table
          {:fx/context
           (fx/create-context {})
           :players fake-players}))))
