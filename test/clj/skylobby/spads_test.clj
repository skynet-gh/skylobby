(ns skylobby.spads-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.spads :as spads]))


(deftest parse-spads-message
  (is (= nil
         (spads/parse-spads-message "")))
  (is (= {:spads-message-type :vote-progress,
          :spads-parsed ["Vote in progress: \"start\" [y:6/7, n:0/6] (29s remaining)"
                         "start"
                         "6"
                         "7"
                         "0"
                         "6"
                         ""
                         "29s"],
          :text "* Vote in progress: \"start\" [y:6/7, n:0/6] (29s remaining)"}
         (spads/parse-spads-message "* Vote in progress: \"start\" [y:6/7, n:0/6] (29s remaining)")))
  (is (= {:spads-message-type :vote-progress,
          :spads-parsed ["Vote in progress: \"rehost\" [y:4/6(9), n:1/6(9), votes:5/9] (9s remaining)"
                         "rehost"
                         "4"
                         "6(9)"
                         "1"
                         "6(9)"
                         ", votes:5/9"
                         "9s"],
          :text "* Vote in progress: \"rehost\" [y:4/6(9), n:1/6(9), votes:5/9] (9s remaining)"}
         (spads/parse-spads-message "* Vote in progress: \"rehost\" [y:4/6(9), n:1/6(9), votes:5/9] (9s remaining)")))
  (is (= {:spads-message-type :vote-progress,
          :spads-parsed ["Vote in progress: \"set map Altored Divide Bar Remake 1.55\" [y:4/5, n:2/4(5)] (25s remaining)"
                         "set map Altored Divide Bar Remake 1.55"
                         "4"
                         "5"
                         "2"
                         "4(5)"
                         ""
                         "25s"],
          :text "* Vote in progress: \"set map Altored Divide Bar Remake 1.55\" [y:4/5, n:2/4(5)] (25s remaining)"}
         (spads/parse-spads-message "* Vote in progress: \"set map Altored Divide Bar Remake 1.55\" [y:4/5, n:2/4(5)] (25s remaining)")))
  (is (= {:spads-message-type :cancelling-vote
          :spads-parsed ["* Cancelling \"set autobalance on\" vote (command executed directly by user)"
                         "set autobalance on"
                         "command executed directly by user"]
          :text "* [teh]cluster1[06] * Cancelling \"set autobalance on\" vote (command executed directly by user)"}
         (spads/parse-spads-message "* [teh]cluster1[06] * Cancelling \"set autobalance on\" vote (command executed directly by user)"))))


(deftest parse-command-message
  (is (= {:command "y"
          :vote :y}
         (spads/parse-command-message "!y")))
  (is (= {:command "vote y"
          :vote :y}
         (spads/parse-command-message "!vote y"))))


(deftest parse-relay-message
  (is (= {:on-behalf-of "skynet"}
         (spads/parse-relay-message "<skynet> test"))))
