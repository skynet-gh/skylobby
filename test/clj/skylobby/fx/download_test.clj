(ns skylobby.fx.download-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.fx.download :as fx]))


(deftest download-window
  (is (map?
        (fx/download-window nil)))
  (is (map?
        (fx/download-window {:show-downloader true}))))
