(ns skylobby.fx.register-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.register :as fx.register]))


(set! *warn-on-reflection* true)


(deftest register-window
  (is (map?
        (fx.register/register-window-impl
          {:fx/context (fx/create-context nil)
           :screen-bounds {}})))
  (is (map?
        (fx.register/register-window-impl
          {:fx/context (fx/create-context {:show-register-window true})
           :screen-bounds {}}))))
