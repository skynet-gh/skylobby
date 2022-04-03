(ns skylobby.task.handler-test
  (:require 
    [clojure.test :refer [deftest is]]
    [skylobby.task.handler :as handler]))


(deftest parse-rapid-progress
  (is (= {:current 332922
          :total 332922}
         (handler/parse-rapid-progress "[Progress] 100% [==============================] 332922/332922")))
  (is (= {:current 0
          :total 0}
         (handler/parse-rapid-progress "[Progress]   0% [         ] 0/0")))
  (is (= {:current 171880
          :total 1024954}
         (handler/parse-rapid-progress "[Progress]  17% [=====         ] 171880/1024954"))))
