(ns spring-lobby.spring-test
  (:require
    [clojure.data]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as string]
    [clojure.test :refer [deftest is testing]]
    [spring-lobby.spring :as spring]))


(declare battle battle-players expected-script-data expected-script-data-players
         expected-script-txt expected-script-txt-players)


(deftest parse-scripttags
  (is (= nil
         (spring/parse-scripttags "")))
  (is (= {:game
          {:modoptions
           {:test "true"}}}
         (spring/parse-scripttags "game/modoptions/test=true")))
  (is (= {:game
          {:mapoptions
           {:fog "0"
            :inv "0"
            :extractorradius "100"
            :metal "normal"
            :tidal "normal"
            :timeofday "day"
            :weather "clear"
            :wind "normal"},
           :modoptions
           {:disablemapdamage "0"
            :fixedallies "1"
            :mo_heatmap "1"
            :mo_preventcombomb "0"
            :critters "1"
            :mo_coop "0"
            :mo_no_close_spawns "1"
            :mo_ffa "0"
            :captureradius "500"
            :anon_ffa "0"
            :allow_buzz "0"
            :shareddynamicalliancevictory "0"
            :capturebonus "0.5"
            :mo_enemycomcount "1"
            :mo_newbie_placer "0"
            :capturetime "30"}}}
         (spring/parse-scripttags "game/mapoptions/fog=0\tgame/mapoptions/inv=0\tgame/mapoptions/extractorradius=100\tgame/mapoptions/metal=normal\tgame/mapoptions/tidal=normal\tgame/mapoptions/timeofday=day\tgame/mapoptions/weather=clear\tgame/mapoptions/wind=normal\tgame/modoptions/allow_buzz=0\tgame/modoptions/anon_ffa=0\tgame/modoptions/critters=1\tgame/modoptions/disablemapdamage=0\tgame/modoptions/fixedallies=1\tgame/modoptions/mo_coop=0\tgame/modoptions/mo_enemycomcount=1\tgame/modoptions/mo_ffa=0\tgame/modoptions/mo_heatmap=1\tgame/modoptions/mo_newbie_placer=0\tgame/modoptions/mo_no_close_spawns=1\tgame/modoptions/mo_preventcombomb=0\tgame/modoptions/shareddynamicalliancevictory=0\tgame/modoptions/capturebonus=0.5\tgame/modoptions/captureradius=500\tgame/modoptions/capturetime=30"))))


(deftest script-data
  (testing "no players"
    (is (= expected-script-data
           (spring/script-data battle))))
  (testing "player and bot"
    (is (= expected-script-data-players
           (spring/script-data battle-players)))))

(deftest script-test
  #_
  (is (= expected-script-txt
         (spring/script-txt
           (sort-by first expected-script-data))))
  #_
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


(deftest parse-script
  #_
  (is (= expected-script-data
         (spring/parse-script expected-script-txt)
         (spring/parse-script expected-script-txt))))


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
    :hostip nil
    :ishost 1}})

(def battle-players
  {:battle-modhash -1
   :battle-version "103.0"
   :battle-map "Dworld Duo"
   :battle-title "deth"
   :battle-modname "Balanced Annihilation V10.24"
   :battle-maphash -1
   :battle-port 8452
   :battle-ip nil ;"192.168.1.6"
   :users
   {"skynet9001"
    {:battle-status
     {:id 0
      :ally 0
      :team-color 0
      :handicap 0
      :side 0}
     :team-color 0}}
   :bots
   {"kekbot1"
    {:ai-name "KAIK"
     :ai-version "0.13"
     :owner "skynet9001"
     :team-color 1
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
    :hostip nil
    :ishost 1
    "team0"
    {:teamleader 0
     :handicap 0
     :allyteam 0
     :rgbcolor "0.0 0.0 0.0"
     :side "ARM"}
    "team1"
    {:teamleader 0
     :handicap 1
     :allyteam 1
     :rgbcolor "0.0 0.0 0.00392156862745098"
     :side "CORE"},
    "allyteam1" {:numallies 0}
    "allyteam0" {:numallies 0}
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
     :countrycode nil
     :spectator 1}}})



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
