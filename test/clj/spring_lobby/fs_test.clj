(ns spring-lobby.fs-test
  (:require
    [clojure.string :as string]
    [clojure.test :refer [deftest is testing]]
    [spring-lobby.fs :as fs]))


(declare mock-parsed-lua mock-map-data-txt)


(deftest spring-root
  (testing "Windows Subsystem for Linux"
    (with-redefs [fs/get-sys-data (constantly
                                    {:os-name "Linux"
                                     :os-version "blahblah Microsoft"
                                     :user-name "me"})]
      (is (= (str "/mnt/c/Users/me/Documents/My Games/Spring")
             (fs/absolute-path
               (fs/spring-root))))))
  (testing "Linux"
    (with-redefs [fs/get-sys-data (constantly
                                    {:os-name "Linux"
                                     :os-version ""
                                     :user-home "/home/me2"})]
      (is (= (str "/home/me2/.spring")
             (fs/absolute-path
               (fs/spring-root))))))
  (testing "Windows"
    (with-redefs [fs/get-sys-data (constantly
                                    {:os-name "somethingWindowssomething"
                                     :os-version "10.0"
                                     :user-home "/C:/Users/me3"})]
      (is (= (str "/C:/Users/me3/Documents/My Games/Spring")
             (fs/absolute-path
               (fs/spring-root)))))))

(deftest springlobby-root
  (testing "Windows Subsystem for Linux"
    (with-redefs [fs/get-sys-data (constantly
                                    {:os-name "Linux"
                                     :os-version "blahblah Microsoft"
                                     :user-name "me"})]
      (is (= (str "/mnt/c/Users/me/AppData/Roaming/springlobby")
             (fs/absolute-path
               (fs/springlobby-root))))))
  (testing "Linux"
    (with-redefs [fs/get-sys-data (constantly
                                    {:os-name "Linux"
                                     :os-version ""
                                     :user-home "/home/me2"})]
      (is (= (str "/home/me2/snap/springlobby-nsg/common/.springlobby")
             (fs/absolute-path
               (fs/springlobby-root))))))
  (testing "Windows"
    (with-redefs [fs/get-sys-data (constantly
                                    {:os-name "somethingWindowssomething"
                                     :os-version "10.0"
                                     :user-home "/C:/Users/me3"})]
      (is (= (str "/C:/Users/me3/AppData/Roaming/springlobby")
             (fs/absolute-path
               (fs/springlobby-root)))))))

(deftest app-root
  (testing "Windows Subsystem for Linux"
    (with-redefs [fs/get-sys-data (constantly
                                    {:os-name "Linux"
                                     :os-version "blahblah Microsoft"
                                     :user-name "me"})]
      (is (= (str "/mnt/c/Users/me/.alt-spring-lobby")
             (fs/absolute-path
               (fs/app-root))))))
  (testing "Linux"
    (with-redefs [fs/get-sys-data (constantly
                                    {:os-name "Linux"
                                     :os-version ""
                                     :user-home "/home/me2"})]
      (is (= (str "/home/me2/.alt-spring-lobby")
             (fs/absolute-path
               (fs/app-root))))))
  (testing "Windows"
    (with-redefs [fs/get-sys-data (constantly
                                    {:os-name "somethingWindowssomething"
                                     :os-version "10.0"
                                     :user-home "/C:/Users/me3"})]
      (is (= (str "/C:/Users/me3/.alt-spring-lobby")
             (fs/absolute-path
               (fs/app-root)))))))

(deftest config-root
  (testing "Windows Subsystem for Linux"
    (with-redefs [fs/get-sys-data (constantly
                                    {:os-name "Linux"
                                     :os-version "blahblah Microsoft"
                                     :user-name "me"})]
      (is (= (str "/mnt/c/Users/me/.alt-spring-lobby/wsl")
             (fs/absolute-path
               (fs/config-root))))))
  (testing "Linux"
    (with-redefs [fs/get-sys-data (constantly
                                    {:os-name "Linux"
                                     :os-version ""
                                     :user-home "/home/me2"})]
      (is (= (str "/home/me2/.alt-spring-lobby")
             (fs/absolute-path
               (fs/config-root))))))
  (testing "Windows"
    (with-redefs [fs/get-sys-data (constantly
                                    {:os-name "somethingWindowssomething"
                                     :os-version "10.0"
                                     :user-home "/C:/Users/me3"})]
      (is (= (str "/C:/Users/me3/.alt-spring-lobby")
             (fs/absolute-path
               (fs/config-root)))))))

(deftest sync-version-to-engine-version
  (is (= "103.0"
         (fs/sync-version-to-engine-version "103")))
  (is (= "104.0"
         (fs/sync-version-to-engine-version "104")))
  (is (= "104.0.1-1553-gd3c0012 maintenance"
         (fs/sync-version-to-engine-version "104.0.1-1553-gd3c0012 maintenance")))
  (is (= "104.0.1-1553-gd3c0012 maintenance"
         (fs/sync-version-to-engine-version "104.0.1-1553-gd3c0012 maintenance"))))


(deftest spring-config-line
  (is (= "Dworld V1, for 16 players free for all. Roads are fast, expand to win! Made by [teh]Beherith (mysterme[at]gmail.com) concept by TP"
         (fs/spring-config-line (string/split-lines mock-map-data-txt) "Description="))))

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
