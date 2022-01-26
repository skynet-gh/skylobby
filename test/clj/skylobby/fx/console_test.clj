(ns skylobby.fx.console-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.console :as fx.console]))


(set! *warn-on-reflection* true)


(deftest console-document
  (is (some?
        (fx.console/console-document
          [{:message-type "PING"
            :message "PING"
            :source :client
            :timestamp 0}]))))


(deftest console-view
  (is (map?
        (fx.console/console-view
          {:fx/context (fx/create-context {:selected-tab-main {"server-key" "console"}
                                           :by-server {"server-key" {:console-log [{:message-type "PING"
                                                                                    :message "PING"
                                                                                    :source :client
                                                                                    :timestamp 0}]}}})
           :server-key "server-key"}))))
