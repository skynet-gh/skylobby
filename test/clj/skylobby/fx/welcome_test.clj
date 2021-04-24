(ns skylobby.fx.welcome-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.fx.welcome :as fx]))


(deftest welcome-view
  (is (map?
        (fx/welcome-view nil))))
