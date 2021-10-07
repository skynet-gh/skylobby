(ns skylobby.fx.welcome-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.welcome :as fx.welcome]))


(deftest connect-button
  (is (map?
        (fx.welcome/connect-button {:fx/context (fx/create-context nil)}))))

(deftest singleplayer-buttons
  (is (map?
        (fx.welcome/singleplayer-buttons {:fx/context (fx/create-context nil)}))))

(deftest multiplayer-buttons
  (is (map?
        (fx.welcome/multiplayer-buttons {:fx/context (fx/create-context nil)}))))

(deftest welcome-view
  (is (map?
        (fx.welcome/welcome-view {:fx/context (fx/create-context nil)})))
  (is (map?
        (fx.welcome/welcome-view
          {:fx/context
           (fx/create-context
             {:by-server {:local {:battle {:battle-id :singleplayer}}}})}))))
