(ns spring-lobby.util-test
  (:require
    [clojure.test :refer [deftest is]]
    [spring-lobby.util :as u])
  (:import
    (java.util TimeZone)))


(deftest battle-channel-name?
  (is (true? (u/battle-channel-name? "__battle__12345")))
  (is (false? (u/battle-channel-name? "@skynet")))
  (is (false? (u/battle-channel-name? "main"))))


(deftest update-console-log
  (let [state-atom (atom {:by-server {"localhost" {:client :fake-client}
                                      "other" {}}})
        now 12345]
    (with-redefs [u/curr-millis (constantly now)]
      (u/update-console-log state-atom :server :fake-client "message"))
    (is (= {:by-server
            {"localhost"
             {:client :fake-client
              :console-log [{:message "message"
                             :timestamp now
                             :source :server}]}
             "other" {}}}
           @state-atom))))


(deftest update-chat-messages-fn
  (let [now 12345]
    (is (= [{:text "m"
             :timestamp now
             :username "me"
             :ex true}]
           (with-redefs [u/curr-millis (constantly now)]
             ((u/update-chat-messages-fn "me" "m" true) []))))))


(deftest parse-skill
  (is (= 25.0
         (u/parse-skill "#25#")))
  (is (= 13.0
         (u/parse-skill "~13")))
  (is (= 33.33
         (u/parse-skill "33.33"))))


(deftest nickname
  (is (= "skynet"
         (u/nickname
           {:username "skynet"})))
  (is (= "bot1 (BARb, skynet)"
         (u/nickname
           {:bot-name "bot1"
            :ai-name "BARb"
            :owner "skynet"}))))


(deftest download-progress
  (is (= "Downloading..."
         (u/download-progress nil)))
  (is (= "0 bytes / 0 bytes"
         (u/download-progress
           {:current 0
            :total 0})))
  (is (= "117 MB / 1 GB"
         (u/download-progress
           {:current 123456789
            :total 1234567890}))))


(deftest mod-name-fix-git
  (is (= "Balanced Annihilation V10.24"
         (u/mod-name-fix-git "Balanced Annihilation V10.24")))
  (is (= "Beyond All Reason $VERSION"
         (u/mod-name-fix-git "Beyond All Reason git:f0cf2cb"))))


(deftest format-hours
  (is (= "00:00:00"
         (u/format-hours (.toZoneId (TimeZone/getTimeZone "UTC")) 0))))
