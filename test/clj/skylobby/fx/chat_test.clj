(ns skylobby.fx.chat-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.chat :as fx.chat]))


(set! *warn-on-reflection* true)


(deftest chat-window-impl
  (is (map?
        (fx.chat/chat-window-impl
          {:fx/context (fx/create-context {:show-chat-window true})}))))
