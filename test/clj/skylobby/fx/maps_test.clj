(ns skylobby.fx.maps-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fs :as fs]
    [skylobby.fx.maps :as fx.maps]))


(set! *warn-on-reflection* true)


(deftest maps-view
  (is (map?
        (fx.maps/maps-view-impl
          {:fx/context (fx/create-context nil)}))))


(deftest maps-window
  (is (map?
        (fx.maps/maps-window
          {:fx/context (fx/create-context nil)
           :screen-bounds {}})))
  (let [f (fs/file ".")]
    (is (map?
          (fx.maps/maps-window
            {:fx/context (fx/create-context {:show-maps true
                                             :spring-isolation-dir f
                                             :by-spring-root {(fs/canonical-path f) {:maps [{:map-name "map1"}
                                                                                            {:map-name "map2"}]}}})
             :screen-bounds {}})))
    (is (map?
          (fx.maps/maps-window
            {:fx/context (fx/create-context {:show-maps true
                                             :spring-isolation-dir f
                                             :filter-maps-name "map1"
                                             :by-spring-root {(fs/canonical-path f) {:maps [{:map-name "map1"}
                                                                                            {:map-name "map2"}]}}})
             :screen-bounds {}})))))
