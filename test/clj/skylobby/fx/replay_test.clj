(ns skylobby.fx.replay-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.fx.replay :as fx]
    [spring-lobby.fs :as fs]))


(deftest replay-view
  (is (map?
        (fx/replay-view nil)))
  (is (map?
        (fx/replay-view
          {:show-sync true
           :selected-replay
           {:header {:engine-version "104.0.1-1784-gf6173b4 BAR"}
            :body
            {:script-data
             {:game
              {:gametype "Beyond All Reason test-16136-e256f38"
               :mapname "DSDR 4.0"
               :team0 {:rgbcolor "0.0 0.0 0.0"}
               :player0 {:team 0}
               :ai0 {:team 0}}}}}
           :engines-by-version {"104.0.1-1784-gf6173b4 BAR" {}}
           :maps-by-version {"DSDR 4.0" {}}
           :mods-by-version {"Beyond All Reason test-16136-e256f38" {}}}))))


(deftest replays-table
  (is (map?
        (fx/replays-table
          {:replays [{:filename ".sdfz"}]})))
  (is (map?
        (fx/replays-table
          {:replays [{:filename ".sdfz"}]
           :replays-window-details true}))))


(deftest replays-window-impl
  (is (map?
        (fx/replays-window-impl nil)))
  (is (map?
        (fx/replays-window-impl {:show-replays true})))
  (is (map?
        (fx/replays-window-impl
          {:show-replays true
           :parsed-replays-by-path
           {"."
            {:filename ".sdfz"}}})))
  (is (map?
        (let [f (fs/file ".")]
          (fx/replays-window-impl
            {:show-replays true
             :parsed-replays-by-path
             {(fs/canonical-path f)
              {:filename ".sdfz"}}
             :selected-replay-file f}))))
  (is (map?
        (let [f (fs/file ".")]
          (fx/replays-window-impl
            {:show-replays true
             :parsed-replays-by-path
             {(fs/canonical-path f)
              {:filename ".sdfz"}}
             :selected-replay-file f
             :filter-replay-source "."
             :filter-replay-type "."
             :filter-replay-min-players 1
             :filter-replay-max-players 2
             :filter-replay ". ."})))))


(deftest standalone-replay-window
  (is (map?
        (fx/standalone-replay-window nil))))
