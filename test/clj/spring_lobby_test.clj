(ns spring-lobby-test
  (:require
    [clojure.test :refer [deftest is]]
    [spring-lobby]
    [spring-lobby.fs :as fs]))


(deftest available-name
  (is (= "bot"
         (spring-lobby/available-name [] "bot")))
  (is (= "bot0"
         (spring-lobby/available-name ["bot"] "bot")))
  (is (= "bot2"
         (spring-lobby/available-name ["bot" "bot0" "bot1"] "bot"))))

(deftest could-be-this-engine?
  (is (true?
        (with-redefs [fs/platform (constantly "win64")]
          (spring-lobby/could-be-this-engine?
            "104.0.1.1828-g1f481b7 BAR"
            {:download-url
             "https://github.com/beyond-all-reason/spring/releases/download/spring_bar_%7BBAR%7D104.0.1-1828-g1f481b7/spring_bar_.BAR.104.0.1-1828-g1f481b7_windows-64-minimal-portable.7z",
             :resource-filename
             "spring_bar_.BAR.104.0.1.1828-g1f481b7_windows-64-minimal-portable.7z"
             :resource-type :spring-lobby/engine,
             :resource-date "2021-03-20T17:30:16Z",
             :download-source-name "BAR GitHub spring",
             :resource-updated 1616282430238})))))
