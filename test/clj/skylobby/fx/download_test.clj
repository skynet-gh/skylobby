(ns skylobby.fx.download-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.download :as fx.download]))


(deftest download-window
  (is (map?
        (fx.download/download-window {:fx/context (fx/create-context nil)})))
  (is (map?
        (fx.download/download-window {:fx/context (fx/create-context {:show-downloader true})}))))
