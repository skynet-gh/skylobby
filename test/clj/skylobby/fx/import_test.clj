(ns skylobby.fx.import-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.import :as fx.import]))


(set! *warn-on-reflection* true)


(deftest importer-root
  (is (map?
        (fx.import/importer-root
          {:fx/context (fx/create-context nil)}))))


(deftest import-window
  (is (map?
        (fx.import/import-window
          {:fx/context (fx/create-context nil)
           :screen-bounds {}})))
  (is (map?
        (fx.import/import-window
          {:fx/context (fx/create-context {:show-importer true})
           :screen-bounds {}}))))
