(ns skylobby.fx.download-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.download :as fx.download]))


(set! *warn-on-reflection* true)


(deftest downloader-root
  (is (map?
        (fx.download/downloader-root
          {:fx/context (fx/create-context nil)})))
  (is (map?
        (fx.download/downloader-root
          {:fx/context (fx/create-context {:downloadables-by-url {"url" {:download-source-name "d"
                                                                         :resource-filename "f1"}}
                                           :download-source-name "d"
                                           :download-filter "f"})}))))


(deftest download-window
  (is (map?
        (fx.download/download-window
          {:fx/context (fx/create-context nil)
           :screen-bounds {}})))
  (is (map?
        (fx.download/download-window
          {:fx/context (fx/create-context {:show-downloader true})
           :screen-bounds {}}))))
