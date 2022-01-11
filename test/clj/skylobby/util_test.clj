(ns skylobby.util-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.util :as u])
  (:import
    (java.util TimeZone)))


(set! *warn-on-reflection* true)


(deftest battle-channel-name?
  (is (true? (u/battle-channel-name? "__battle__12345")))
  (is (false? (u/battle-channel-name? "@skynet")))
  (is (false? (u/battle-channel-name? "main"))))


(deftest client-id
  (is (= 0
         (u/client-id (atom nil) {:client-id-type "zero"})))
  (let [a (atom nil)
        id (u/client-id a {:client-id-type "random"})]
    (is (not= 0 id))
    (is (= id
           (:client-id-override @a))))
  (is (not= 0
        (u/client-id (atom nil) {:client-id-type "hardware"}))))


(deftest append-console-log
  (let [state-atom (atom {:by-server {"skynet@localhost" {}
                                      "other" {}}})
        now 12345]
    (with-redefs [u/curr-millis (constantly now)]
      (u/append-console-log state-atom "skynet@localhost" :server "message test"))
    (is (= {:by-server
            {"skynet@localhost"
             {:console-log [{:message "message test"
                             :message-type "message"
                             :timestamp now
                             :source :server}]}
             "other" {}}}
           @state-atom))))


(deftest update-chat-messages-fn
  (let [now 12345]
    (is (= [{:text "m"
             :timestamp now
             :username "me"
             :message-type :ex
             :spads nil
             :vote nil
             :relay nil}]
           (with-redefs [u/curr-millis (constantly now)]
             ((u/update-chat-messages-fn "me" "m" true) []))))
    (is (= [{:text "!y"
             :timestamp now
             :username "me"
             :message-type nil
             :spads nil
             :vote {:command "y"
                    :vote :y}
             :relay nil}]
           (with-redefs [u/curr-millis (constantly now)]
             ((u/update-chat-messages-fn "me" "!y" false) []))))
    (is (= [{:text "<you> blah"
             :timestamp now
             :username "me"
             :message-type nil
             :spads nil
             :vote nil
             :relay {:on-behalf-of "you"}}]
           (with-redefs [u/curr-millis (constantly now)]
             ((u/update-chat-messages-fn "me" "<you> blah" false) []))))))


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
  (is (= "..."
         (u/download-progress nil)))
  (is (= "0 bytes / 0 bytes"
         (u/download-progress
           {:current 0
            :total 0})))
  (is (= "117 MB / 1 GB"
         (u/download-progress
           {:current 123456789
            :total 1234567890}))))


(deftest mod-name-git-no-ref
  (is (= "Beyond All Reason git:"
         (u/mod-name-git-no-ref "Beyond All Reason git:f0cf2cb")))
  (is (= nil
         (u/mod-name-git-no-ref "Balanced Annihilation V10.24"))))

(deftest mod-git-ref
  (is (= "f0cf2cb"
         (u/mod-git-ref "Beyond All Reason git:f0cf2cb")))
  (is (= nil
         (u/mod-git-ref "Balanced Annihilation V10.24"))))

(deftest mod-name-fix-git
  (is (= "Balanced Annihilation V10.24"
         (u/mod-name-fix-git "Balanced Annihilation V10.24")))
  (is (= "Beyond All Reason $VERSION"
         (u/mod-name-fix-git "Beyond All Reason git:f0cf2cb"))))


(deftest format-hours
  (is (= "00:00:00"
         (u/format-hours (.toZoneId (TimeZone/getTimeZone "UTC")) 0))))


(deftest format-datetime
  (is (= "19700101-000000"
         (u/format-datetime (.toZoneId (TimeZone/getTimeZone "UTC")) 0))))


(deftest non-battle-channels
  (is (= [{:channel-name "@skynet"}
          {:channel-name "main"}]
         (u/non-battle-channels
           [{:channel-name "@skynet"}
            {:channel-name "main"}
            {:channel-name "__battle__12345"}]))))


(deftest spring-script-color-to-int
  (is (= 4278190080 ; 255 alpha
         (u/spring-script-color-to-int "0.0 0.0 0.0")))
  (is (= 4287518238
         (u/spring-script-color-to-int "0.12 0.34 0.56"))))


(deftest base64-md5
  (is (= "1B2M2Y8AsgTpgAmY7PhCfg=="
         (u/base64-md5 ""))))
