(ns skylobby.fx.replay-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fs :as fs]
    [skylobby.fx.replay :as fx.replay]))


(set! *warn-on-reflection* true)


(deftest chat-log-document
  (is (some?
        (fx.replay/chat-log-document
          [{:command 7}
           {:command 31
            :map-draw-action 0}
           {:command 51
            :team-action 2}
           {:command 3}]
          {}))))


(deftest replay-view
  (is (map?
        (fx.replay/replay-view {:fx/context (fx/create-context nil)})))
  (is (map?
        (fx.replay/replay-view
          {:fx/context
           (fx/create-context
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
              :mods-by-version {"Beyond All Reason test-16136-e256f38" {}}})}))))


(deftest replays-table
  (is (map?
        (fx.replay/replays-table
          {:fx/context
           (fx/create-context
             {:replays [{:filename ".sdfz"}]})})))
  (is (map?
        (fx.replay/replays-table
          {:fx/context
           (fx/create-context
             {:replays [{:filename ".sdfz"}]
              :replays-window-details true})}))))


(deftest replays-root
  (is (map?
        (fx.replay/replays-root
          {:fx/context
           (fx/create-context
             {:parsed-replays-by-path
              {"."
               {:filename ".sdfz"}}})})))
  (is (map?
        (let [f (fs/file ".")]
          (fx.replay/replays-root
            {:fx/context
             (fx/create-context
               {
                :parsed-replays-by-path
                {(fs/canonical-path f)
                 {:filename ".sdfz"}}
                :selected-replay-file f})}))))
  (is (map?
        (let [f (fs/file ".")]
          (fx.replay/replays-root
            {:fx/context
             (fx/create-context
               {
                :parsed-replays-by-path
                {(fs/canonical-path f)
                 {:filename ".sdfz"}}
                :selected-replay-file f
                :filter-replay-source "."
                :filter-replay-type "."
                :filter-replay-min-players 1
                :filter-replay-max-players 2
                :filter-replay ". ."})})))))

(deftest replays-window-impl
  (is (map?
        (fx.replay/replays-window-impl
          {:fx/context (fx/create-context nil)
           :screen-bounds {}})))
  (is (map?
        (fx.replay/replays-window-impl
          {:fx/context (fx/create-context {:show-replays true})
           :screen-bounds {}}))))


(deftest standalone-replay-window
  (is (map?
        (fx.replay/standalone-replay-window
          {:fx/context (fx/create-context nil)
           :screen-bounds {}}))))
