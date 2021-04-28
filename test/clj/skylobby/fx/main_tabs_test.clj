(ns skylobby.fx.main-tabs-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.fx.main-tabs :as fx]))


(deftest main-tab-view
  (is (map?
        (fx/main-tab-view nil))))
