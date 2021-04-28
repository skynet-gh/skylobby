(ns skylobby.fx.channel-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.fx.channel :as fx]))


(deftest channel-view
  (is (map?
        (fx/channel-view nil))))
