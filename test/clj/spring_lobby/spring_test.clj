(ns spring-lobby.spring-test
  (:require
    [clojure.data]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as string]
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
  (testing "with players"
    (let [expected expected-script-txt-players
          actual (spring/script-txt
                   (sort-by first expected-script-data-players))]
      (is (= expected actual))
      (when (not= expected actual)
        (println (str "expected:\n" expected))
        (println (str "actual:\n" actual))
        (let [diff (clojure.data/diff (string/split-lines expected) (string/split-lines actual))]
          (println "diff:")
          (pprint diff))))))


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
      :handicap 0
      :side 0}}}
   :bots
   {"kekbot1"
    {:ai-name "KAIK"
     :ai-version "0.13"
     :owner "skynet9001"
     :battle-status
     {:id 1
      :ally 1
      :team-color 1
      :handicap 1
      :side 1}}}})

(def expected-script-data-players
  {:game
   {:gametype "Balanced Annihilation V10.24"
    :mapname "Dworld Duo"
    :hostport 8452
    :hostip "192.168.1.6"
    :ishost 1
    :numplayers 1
    :startpostype 2
    :numusers 2,
    "team0"
    {:teamleader 0
     :handicap 0
     :allyteam 0
     :rgbcolor "0 0 0"
     :side 0},
    "team1"
    {:teamleader 1
     :handicap 1
     :allyteam 1
     :rgbcolor "0 0 1"
     :side 1},
    "allyteam1" {},
    "allyteam0" {},
    "ai1"
    {:name "kekbot1"
     :shortname "KAIK"
     :version "0.13"
     :host 0
     :team 1,
     :isfromdemo 0
     :options {}},
    "player0"
    {:name "skynet9001",
     :team 0,
     :isfromdemo 0,
     :countrycode nil}}})



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
\t[ai1]
\t{
\t\thost = 0;
\t\tisfromdemo = 0;
\t\tname = kekbot1;
\t\t[options]
\t\t{
\t\t}

\t\tshortname = KAIK;
\t\tteam = 1;
\t\tversion = 0.13;
\t}

\t[allyteam0]
\t{
\t}

\t[allyteam1]
\t{
\t}

\tgametype = Balanced Annihilation V10.24;
\thostip = 192.168.1.6;
\thostport = 8452;
\tishost = 1;
\tmapname = Dworld Duo;
\tnumplayers = 1;
\tnumusers = 2;
\t[player0]
\t{
\t\tcountrycode = ;
\t\tisfromdemo = 0;
\t\tname = skynet9001;
\t\tteam = 0;
\t}

\tstartpostype = 2;
\t[team0]
\t{
\t\tallyteam = 0;
\t\thandicap = 0;
\t\trgbcolor = 0 0 0;
\t\tside = 0;
\t\tteamleader = 0;
\t}

\t[team1]
\t{
\t\tallyteam = 1;
\t\thandicap = 1;
\t\trgbcolor = 0 0 1;
\t\tside = 1;
\t\tteamleader = 1;
\t}

}

")
