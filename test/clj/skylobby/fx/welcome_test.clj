(ns skylobby.fx.welcome-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.fx.welcome :as fx]))


(deftest connect-button
  (is (map?
        (fx/connect-button nil))))

(deftest singleplayer-buttons
  (is (map?
        (fx/singleplayer-buttons nil))))

(deftest multiplayer-buttons
  (is (map?
        (fx/multiplayer-buttons nil))))

(deftest welcome-view
  (is (map?
        (fx/welcome-view nil)))
  (is (map?
        (fx/welcome-view {:by-server {:local {:battle {:battle-id :singleplayer}}}}))))
