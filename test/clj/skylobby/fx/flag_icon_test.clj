(ns skylobby.fx.flag-icon-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.fx.flag-icon :as fx.flag-icon]))


(set! *warn-on-reflection* true)


(deftest flag-icon
  (is (map?
        (fx.flag-icon/flag-icon
          {:country-code "US"}))))
