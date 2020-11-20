(ns tests
  (:require
    [com.jakemccrary.test-refresh :refer [monitor-project]]
    hashp.core
    [pjstadig.humane-test-output]))


(defn -main []
  (pjstadig.humane-test-output/activate!)
  (monitor-project
    ["test/clj"]
    {:nses-and-selectors [:ignore [[(constantly true)]]]
     :do-not-monitor-keystrokes true
     :with-repl false}))
