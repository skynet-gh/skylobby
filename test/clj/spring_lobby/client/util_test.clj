(ns spring-lobby.client.util-test
  (:require
    [clojure.test :refer [deftest is]]
    [spring-lobby.client.util :as cu]))


(deftest decode-battle-status
  (is (= {:ready false,
          :ally 0,
          :handicap 0,
          :mode true,
          :sync 2,
          :id 0,
          :side 0}
         (cu/decode-battle-status "8389632"))))

(deftest encode-battle-status
  (let [encoded (cu/encode-battle-status
                  {:ready false,
                   :ally 0,
                   :handicap 0,
                   :mode true,
                   :sync 2,
                   :id 0,
                   :side 0})]
    (is (= "8389632"
           encoded))))
