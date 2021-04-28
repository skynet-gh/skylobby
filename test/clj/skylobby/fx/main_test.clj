(ns skylobby.fx.main-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.fx.main :as fx]))


(deftest main-window
  (is (map?
        (fx/main-window nil))))
