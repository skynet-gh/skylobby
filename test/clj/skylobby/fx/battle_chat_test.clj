(ns skylobby.fx.battle-chat-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.battle-chat :as fx.battle-chat]))


(set! *warn-on-reflection* true)


(deftest battle-chat-window-impl
  (is (map?
        (fx.battle-chat/battle-chat-window-impl
          {:fx/context (fx/create-context {})}))))
