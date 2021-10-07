(ns skylobby.fx.rapid-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.rapid :as fx.rapid]))


(deftest rapid-download-window
  (is (map?
        (fx.rapid/rapid-download-window {:fx/context (fx/create-context nil)})))
  (is (map?
        (fx.rapid/rapid-download-window {:fx/context (fx/create-context {:show-rapid-downloader true})}))))
