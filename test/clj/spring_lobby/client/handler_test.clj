(ns spring-lobby.client.handler-test
  (:require
    [clojure.test :refer [deftest is]]
    [spring-lobby.client.handler :as handler]))


(deftest parse-addbot
  (is (= ["12" "kekbot1" "skynet9001" "0" "0" "KAIK|0.13"]
         (rest (handler/parse-addbot "ADDBOT 12 kekbot1 skynet9001 0 0 KAIK|0.13")))))

(deftest parse-joinbattle
  (is (= ["32" "-1706632985" "__battle__1"]
         (rest
           (handler/parse-joinbattle "JOINBATTLE 32 -1706632985 __battle__1")))))
