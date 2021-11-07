(ns skylobby.fx.channel-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.channel :as fx.channel]))


(deftest channel-view
  (is (map?
        (fx.channel/channel-view {:fx/context (fx/create-context nil)}))))
