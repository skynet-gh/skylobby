(ns skylobby.fx.main-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.main :as fx.main]))


(set! *warn-on-reflection* true)


(deftest main-window
  (is (map?
        (fx.main/main-window
          {:fx/context (fx/create-context nil)}))))
