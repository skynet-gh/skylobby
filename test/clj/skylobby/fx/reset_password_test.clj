(ns skylobby.fx.reset-password-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.reset-password :as fx.reset-password]))


(set! *warn-on-reflection* true)


(deftest reset-password-window
  (is (map?
        (fx.reset-password/reset-password-window-impl
          {:fx/context (fx/create-context nil)})))
  (is (map?
        (fx.reset-password/reset-password-window-impl
          {:fx/context (fx/create-context {:show-reset-password-window true})}))))
