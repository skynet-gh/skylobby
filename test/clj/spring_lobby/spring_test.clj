(ns spring-lobby.spring-test
  (:require
    [clojure.test :refer [deftest is]]
    [spring-lobby.spring :as spring]))


(declare battle expected-script-data expected-script-txt)


(deftest script-test
  (let [data (spring/script-data battle)]
    (is (= expected-script-data
           data))
    (is (= expected-script-txt
           (spring/script-txt data)))))


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
    :numusers 0
    :myplayername "skynet9001"}})


(def expected-script-txt
  "[game]
{
\tmyplayername = skynet9001;
\tishost = 1;
\tstartpostype = 2;
\tnumplayers = 1;
\tgametype = Balanced Annihilation V9.79.4;
\thostip = 127.0.0.1;
\thostport = 8452;
\tmapname = Dworld Acidic;
\tnumusers = 0;
}

")

#_
(println expected-script-txt)
