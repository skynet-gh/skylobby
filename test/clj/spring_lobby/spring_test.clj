(ns spring-lobby.spring-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [spring-lobby.spring :as spring]))


(declare battle battle-players expected-script-data expected-script-data-players 
         expected-script-txt expected-script-txt-players)


(deftest script-data
  (testing "no players"
    (is (= expected-script-data
           (spring/script-data battle))))
  (testing "player and bot"
    (is (= expected-script-data-players
           (spring/script-data battle-players)))))

(deftest script-test
  (is (= expected-script-txt
         (spring/script-txt
           (sort-by first expected-script-data))))
  (is (= expected-script-txt-players
         (spring/script-txt
           (sort-by first expected-script-data-players)))))


(def battle
  {:battle-modhash -1706632985
   :battle-version "104.0.1-1510-g89bb8e3 maintenance"
   :battle-map "Dworld Acidic"
   :battle-title "deth"
   :battle-modname "Balanced Annihilation V9.79.4"
   :battle-maphash -1611391257
   :battle-port 8452
   :battle-ip "127.0.0.1"})

(def expected-script-data
  {:game
   {:gametype "Balanced Annihilation V9.79.4"
    :mapname "Dworld Acidic"
    :hostport 8452
    :hostip "127.0.0.1"
    :ishost 1
    :numplayers 1
    :startpostype 2
    :numusers 0}})

(def battle-players
  {:battle-modhash -1
   :battle-version "103.0"
   :battle-map "Dworld Duo"
   :battle-title "deth"
   :battle-modname "Balanced Annihilation V10.24"
   :battle-maphash -1
   :battle-port 8452
   :battle-ip "192.168.1.6"
   :users
   {"skynet9001"
    {:battle-status
     {:id 0
      :ally 0
      :team-color 0
      :handicap 1}}}
   :bots
   {"kekbot1"
    {:battle-status
     {:id 1
      :ally 1
      :team-color 1
      :handicap 1}}}})

(def expected-script-data-players
  {:game
   {:gametype "Balanced Annihilation V10.24"
    :mapname "Dworld Duo"
    :hostport 8452
    :hostip "192.168.1.6"
    :ishost 1
    :numplayers 1
    :startpostype 2
    :numusers 0}})


(def expected-script-txt
  "[game]
{
\tgametype = Balanced Annihilation V9.79.4;
\thostip = 127.0.0.1;
\thostport = 8452;
\tishost = 1;
\tmapname = Dworld Acidic;
\tnumplayers = 1;
\tnumusers = 0;
\tstartpostype = 2;
}

")

(def expected-script-txt-players
  "[game]
{
\tgametype = Balanced Annihilation V9.79.4;
\thostip = 127.0.0.1;
\thostport = 8452;
\tishost = 1;
\tmapname = Dworld Acidic;
\tnumplayers = 1;
\tnumusers = 0;
\tstartpostype = 2;
}

")
