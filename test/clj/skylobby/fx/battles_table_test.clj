(ns skylobby.fx.battles-table-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.battles-table :as fx.battles-table]))


(set! *warn-on-reflection* true)


(deftest battles-table
  (is (map?
        (fx.battles-table/battles-table-impl
          {:fx/context (fx/create-context nil)})))
  (is (map?
        (fx.battles-table/battles-table-impl
          {:fx/context (fx/create-context {:by-server {"server" {:battles {"1" {:battle-title "b1"}}}}})
           :server-key "server"}))))

(deftest battles-table-with-images
  (is (map?
        (fx.battles-table/battles-table-with-images-impl
          {:fx/context (fx/create-context nil)})))
  (is (map?
        (fx.battles-table/battles-table-with-images-impl
          {:fx/context (fx/create-context {:by-server {"server" {:battles {"1" {:battle-title "b1"}}}}})
           :server-key "server"}))))
