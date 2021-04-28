(ns skylobby.fx.rapid-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.fx.rapid :as fx]))


(deftest rapid-download-window
  (is (map?
        (fx/rapid-download-window nil)))
  (is (map?
        (fx/rapid-download-window {:show-rapid-downloader true}))))
