(ns skylobby.resource-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [skylobby.resource :as resource]
    [spring-lobby.fs :as fs]))


(deftest resource-dest
  (let [root (fs/default-isolation-dir)]
    (is (= nil
           (resource/resource-dest root nil)))
    (is (= (io/file (fs/download-dir) "engine" "104.0.1-1759-g77857fe bar")
           (resource/resource-dest root
             {:resource-type :spring-lobby/engine
              :resource-filename "104.0.1-1759-g77857fe bar"})))
    (is (= (io/file (fs/download-dir) "engine" "spring_bar_.BAR.104.0.1-1694-gf8dd574_linux-64-minimal-portable.7z")
           (resource/resource-dest root
             {:resource-type :spring-lobby/engine
              :resource-filename "spring_bar_.BAR.104.0.1-1694-gf8dd574_linux-64-minimal-portable.7z"})))
    (is (= (io/file root "packages" "387141e832afa99238918d86e9e5b8e2.sdp")
           (resource/resource-dest root
             {:resource-type :spring-lobby/mod
              :resource-filename "387141e832afa99238918d86e9e5b8e2.sdp"})))
    (is (= (io/file root "games" "balanced_annihilation-v9.78.5.sdz")
           (resource/resource-dest root
             {:resource-type :spring-lobby/mod
              :resource-filename "balanced_annihilation-v9.78.5.sdz"})))
    (is (= (io/file root "maps" "dsdr_4.0.sd7")
           (resource/resource-dest root
             {:resource-type :spring-lobby/map
              :resource-filename "dsdr_4.0.sd7"})))
    (is (= (io/file root "maps" "expanded_confluence_dry.sdz")
           (resource/resource-dest root
             {:resource-type :spring-lobby/map
              :resource-filename "expanded_confluence_dry.sdz"})))
    (is (= (io/file root "demos" "20210419_171940_DSDR 4_104.0.1-1828-g1f481b7 BAR.sdfz")
           (resource/resource-dest root
             {:resource-type :spring-lobby/replay
              :resource-filename "20210419_171940_DSDR 4_104.0.1-1828-g1f481b7 BAR.sdfz"})))))


(deftest could-be-this-engine?
  (is (true?
        (with-redefs [fs/platform (constantly "win64")]
          (resource/could-be-this-engine?
            "104.0.1.1828-g1f481b7 BAR"
            {:download-url
             "https://github.com/beyond-all-reason/spring/releases/download/spring_bar_%7BBAR%7D104.0.1-1828-g1f481b7/spring_bar_.BAR.104.0.1-1828-g1f481b7_windows-64-minimal-portable.7z"
             :resource-filename
             "spring_bar_.BAR.104.0.1.1828-g1f481b7_windows-64-minimal-portable.7z"
             :resource-type :spring-lobby/engine
             :resource-date "2021-03-20T17:30:16Z"
             :download-source-name "BAR GitHub spring"
             :resource-updated 1616282430238})))))


(deftest normalize-map
  (is (= "seth_s_ravine_3.1"
         (resource/normalize-map "Seth's Ravine 3.1")))
  (is (= "seth_s_ravine_3.1"
         (resource/normalize-map "seth_s_ravine_3.1.sd7"))))


(deftest could-be-this-map?
  (is (true?
        (resource/could-be-this-map?
          "Zed 2.3 - MexesFix"
          {:resource-filename "zed_2.3_-_mexesfix.sdz"
           :resource-type :spring-lobby/map})))
  (is (false?
        (resource/could-be-this-map?
          "Zed 2.2"
          {:resource-filename "zed_2.3_-_mexesfix.sdz"
           :resource-type :spring-lobby/map})))
  (is (false?
        (resource/could-be-this-map?
          "Zed 2.2"
          {:resource-filename "zed_2.3_-_mexesfix.sdz"
           :resource-type :spring-lobby/map})))
  (is (true?
        (resource/could-be-this-map?
          "Zed 2.2"
          {:resource-filename "zed_2.2.sd7"
           :resource-type :spring-lobby/map})))
  (is (true?
        (resource/could-be-this-map?
          "Seth's Ravine 3.1"
          {:resource-filename "seth_s_ravine_3.1.sd7"
           :resource-type :spring-lobby/map})))
  (is (true?
        (resource/could-be-this-map?
          "Techno Lands Final 9.0"
          {:resource-filename "techno_lands_final_v9.0.sdz"
           :resource-type :spring-lobby/map}))))


(deftest normalize-mod
  (is (= "evolution_rts___16.11"
         (resource/normalize-mod "Evolution RTS - v16.11")))
  (is (= "evolution_rts1611"
         (resource/normalize-mod "Evolution-RTSv1611.sdz"))))

(deftest normalize-mod-harder
  (is (= "evolutionrts1611"
         (resource/normalize-mod-harder "Evolution RTS - v16.11")))
  (is (= "evolutionrts1611"
         (resource/normalize-mod-harder "Evolution-RTSv1611.sdz"))))

(deftest could-be-this-mod?
  (is (true?
        (resource/could-be-this-mod?
          "Evolution RTS - v16.01"
          {:resource-filename "evolution_rts_--v16.01.sdz"})))
  (is (false?
        (resource/could-be-this-mod?
          "Balanced Annihilation V11.0.1"
          {:resource-filename "balanced_annihilation-v11.1.0.sdz"})))
  (is (true?
        (resource/could-be-this-mod?
          "Balanced Annihilation V12.1.0"
          {:resource-filename "balanced_annihilation-v12.1.0.sdz"})))
  (is (true?
        (resource/could-be-this-mod?
          "Beyond All Reason test-16091-cc46531"
          {:resource-name "Beyond All Reason test-16091-cc46531"})))
  (is (true?
        (resource/could-be-this-mod?
          "Evolution RTS - v16.11"
          {:resource-filename "Evolution-RTSv1611.sdz"})))
  (is (true?
        (resource/could-be-this-mod?
          "Total Atomization Prime 2.6RC5"
          {:resource-filename "TAPrime_v2.6RC5.sdz"})))
  (is (true?
        (resource/could-be-this-mod?
          "Evolution RTS - v16.20"
          {:resource-filename "Evolution-RTS-v16.20.sdz"}))))


(deftest same-resource-file?
  (is (true?
        (resource/same-resource-file?
          {:resource-file (io/file ".")}
          {:resource-file (io/file ".")})))
  (is (false?
        (resource/same-resource-file?
          {:resource-file (io/file ".")}
          {:resource-file (io/file "..")}))))

(deftest same-resource-filename?
  (is (true?
        (resource/same-resource-filename?
          {:resource-filename "."}
          {:resource-filename "."})))
  (is (false?
        (resource/same-resource-filename?
          {:resource-filename "."}
          {:resource-filename ".."}))))
