(ns skylobby.fx.import-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.fx.import :as fx]))


(deftest import-window
  (is (map?
        (fx/import-window nil)))
  (is (map?
        (fx/import-window {:show-importer true}))))
