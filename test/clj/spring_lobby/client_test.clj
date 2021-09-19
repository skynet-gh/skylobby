(ns spring-lobby.client-test
  (:require
    [clojure.test :refer [deftest is]]
    [spring-lobby.client :as client]))


(deftest decode-client-status
  (is (= {:bot false,
          :access false,
          :rank 0,
          :away false,
          :ingame false}
         (client/decode-client-status "0")))
  (is (= {:bot false,
          :access false,
          :rank 0,
          :away false,
          :ingame true}
         (client/decode-client-status "1")))
  (is (= {:bot false,
          :access false,
          :rank 2,
          :away false,
          :ingame false}
         (client/decode-client-status "8")))
  (is (= {:bot true,
          :access false,
          :rank 0,
          :away false,
          :ingame false}
         (client/decode-client-status "64"))))
