(ns skylobby.fx.channel-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.channel :as fx.channel]))


(set! *warn-on-reflection* true)


(deftest channel-document
  (is (some?
        (fx.channel/channel-document
          [{:message-type :ex
            :text "message"
            :timestamp 0
            :username "me"}])))
  (is (some?
        (fx.channel/channel-document
          [{:message-type nil
            :text "message 2"
            :timestamp 1
            :username "me"}]))))


(deftest channel-view-history-impl
  (is (map?
        (fx.channel/channel-view-history-impl
          {:fx/context (fx/create-context nil)}))))


(deftest channel-view-input
  (is (map?
        (fx.channel/channel-view-input
          {:fx/context (fx/create-context nil)}))))


(deftest channel-view-users
  (is (map?
        (fx.channel/channel-view-users
          {:fx/context (fx/create-context nil)}))))


(deftest channel-view
  (is (map?
        (fx.channel/channel-view
          {:fx/context (fx/create-context nil)}))))
