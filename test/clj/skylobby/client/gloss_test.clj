(ns skylobby.client.gloss-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.client.gloss :as cu]))


(deftest decode-client-status
  (is (= {:bot false,
          :access false,
          :rank 0,
          :away false,
          :ingame false}
         (cu/decode-client-status "0")))
  (is (= {:bot false,
          :access false,
          :rank 0,
          :away false,
          :ingame true}
         (cu/decode-client-status "1")))
  (is (= {:bot false,
          :access false,
          :rank 2,
          :away false,
          :ingame false}
         (cu/decode-client-status "8")))
  (is (= {:bot true,
          :access false,
          :rank 0,
          :away false,
          :ingame false}
         (cu/decode-client-status "64"))))



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
