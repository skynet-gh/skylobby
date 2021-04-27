(ns skylobby.fx.replay-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.fx.replay :as fx]))


(deftest replays-window
  (is (map?
        (fx/replays-window nil)))
  (is (map?
        (fx/replays-window {:show-replays true})))
  (is (map?
        (fx/replays-window
          {:show-replays true
           :parsed-replays-by-path
           {"."
            {:filename ".sdfz"}}}))))
