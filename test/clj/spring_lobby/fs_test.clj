(ns spring-lobby.fs-test
  (:require
    [clojure.string :as string]
    [clojure.test :refer [deftest is]]
    [spring-lobby.fs :as fs]))


(declare mock-parsed-lua mock-map-data-txt)


(deftest parse-modinfo
  (is (= {:name "Balanced Annihilation"
          :description "Balanced Annihilation"
          :shortname "BA"
          :version "V9.79.4"
          :mutator "Official"
          :game "Total Annihilation"
          :shortGame "TA"
          :modtype 1}
         (fs/parse-modinfo mock-parsed-lua))))

(deftest spring-config-line
  (is (= "Dworld V1, for 16 players free for all. Roads are fast, expand to win! Made by [teh]Beherith (mysterme[at]gmail.com) concept by TP"
         (fs/spring-config-line (string/split-lines mock-map-data-txt) "Description="))))

(deftest parse-map-data
  (is (= {:map
          {:description "Dworld V1, for 16 players free for all. Roads are fast, expand to win! Made by [teh]Beherith (mysterme[at]gmail.com) concept by TP"
           :gravity 120
           :maxmetal 1.23
           :tidalstrength 13
           :extractorradius 85
           :maphardness 100
           :autoshowmetal 1
           :detailtex "detailtexblurred.bmp"
           :smf
           {:maxheight 532
            :minheight -200}}}
         (fs/parse-map-data mock-map-data-txt))))


(def mock-parsed-lua
  [:chunk
   [:block
    [:retstat
     "return"
     [:explist
      [:exp
       [:tableconstructor
        "{"
        [:fieldlist
         [:field "name" "=" [:exp [:string "'Balanced Annihilation'"]]]
         [:fieldsep ","]
         [:field
          "description"
          "="
          [:exp [:string "'Balanced Annihilation'"]]]
         [:fieldsep ","]
         [:field "shortname" "=" [:exp [:string "'BA'"]]]
         [:fieldsep ","]
         [:field "version" "=" [:exp [:string "'V9.79.4'"]]]
         [:fieldsep ","]
         [:field "mutator" "=" [:exp [:string "'Official'"]]]
         [:fieldsep ","]
         [:field "game" "=" [:exp [:string "'Total Annihilation'"]]]
         [:fieldsep ","]
         [:field "shortGame" "=" [:exp [:string "'TA'"]]]
         [:fieldsep ","]
         [:field "modtype" "=" [:exp [:number "1"]]]
         [:fieldsep ","]]
        "}"]]]]
    "<EOF>"]])


(def mock-map-data-txt
  "[MAP]
{
  Description=Dworld V1, for 16 players free for all. Roads are fast, expand to win! Made by [teh]Beherith (mysterme[at]gmail.com) concept by TP;
  Gravity=120;                //in units/sec^2
  MaxMetal=1.23;                //how much metal a map square with the maximum metal value gives
  TidalStrength=13;
  ExtractorRadius=85;       //radius that a single extractor(mine) extracts from
  MapHardness=100;                                // how hard it is to create craters in map, default 100
  AutoShowMetal=1;
  Detailtex=detailtexblurred.bmp;
[SMF]
{
minheight = -200;
maxheight = 532;
}
}")
