(ns skylobby.fx.bottom-bar-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fs :as fs]
    [skylobby.fx.bottom-bar :as fx.bottom-bar]))


(set! *warn-on-reflection* true)


(deftest app-update-button
  (is (map?
        (fx.bottom-bar/app-update-button
          {:fx/context (fx/create-context nil)})))
  (is (map?
        (fx.bottom-bar/app-update-button
          {:fx/context (fx/create-context {:app-update-available true})}))))


(deftest bottom-bar-impl
  (is (map?
        (fx.bottom-bar/bottom-bar-impl
          {:fx/context (fx/create-context nil)})))
  (is (map?
        (fx.bottom-bar/bottom-bar-impl
          {:fx/context (fx/create-context {:music-dir "."})})))
  (is (map?
        (fx.bottom-bar/bottom-bar-impl
          {:fx/context (fx/create-context {:music-dir "."
                                           :music-now-playing (fs/file ".")
                                           :music-queue [:song1 :song2]})}))))
