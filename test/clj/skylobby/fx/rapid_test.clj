(ns skylobby.fx.rapid-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.rapid :as fx.rapid]))


(set! *warn-on-reflection* true)


(deftest rapid-download-root
  (is (map?
        (fx.rapid/rapid-download-root
          {:fx/context (fx/create-context nil)}))))


(deftest rapid-download-window
  (is (map?
        (fx.rapid/rapid-download-window
          {:fx/context (fx/create-context nil)
           :screen-bounds {}})))
  (is (map?
        (fx.rapid/rapid-download-window
          {:fx/context (fx/create-context {:show-rapid-downloader true})
           :screen-bounds {}}))))
