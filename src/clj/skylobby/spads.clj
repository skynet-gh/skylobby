(ns skylobby.spads
  (:require
    [clojure.string :as string]))


(set! *warn-on-reflection* true)


(def spads-message-types
  [
   [:vote-progress #"Vote in progress: \"(.*)\" \[y:(.*)/(.*), n:(\d+)/([^,]+)(.*)\] \((.*) remaining\)"]
   [:greeting #"Hi (.*)! Current battle type is (.*)\."]
   [:called-vote #"\* (.*) called a vote for command \"(.*)\" \[!vote y, !vote n, !vote b\]"]
   [:allowed-vote #"User\(s\) allowed to vote: (.*)"]
   [:vote-passed #"Vote for command \"(.*)\" passed(.*)\."]
   [:vote-failed #"Vote for command \"(.*)\" failed(.*)\."]
   [:awaiting-votes #"Awaiting following vote\(s\): (.*)"]
   [:ringing #"Ringing (.*)"]
   [:balance #"Balancing according to current balance mode: (.*) \((.*)balance deviation: (.*)%\)"]
   [:add-spec #"Adding user (.*) as spectator"]
   [:already-added #"Player \"(.*)\" has already been added (.*)"]
   [:away-vote #"Away vote mode for (.*)"]
   [:unable-to-start-game #"Unable to start game, (.*)"]
   [:game-time #"A game is in progress since (.*)\."]
   [:promoting #"Promoting battle (.*)"]
   [:unable-to-ring #" Unable to ring (.*) \(ring spam protection, please wait (.*)\)"]
   [:flood-protection #"\* (.*), please wait (.*) before calling another vote \(vote flood protection\)\."]
   [:already-voted #"\* (.*), you have already voted for current vote\."]
   [:allowed-to-vote #"(.*) users allowed to vote."]
   [:not-allowed-vote #"\* (.*), you are not allowed to vote for current vote."]
   [:not-allowed-value #"Value \"(.*)\" for (.*) setting \"(.*)\" is not allowed in current preset"]
   [:no-vote #"\* (.*), you cannot vote currently, there is no vote in progress."]
   [:already-a-vote #"(.*), there is already a vote in progress, please wait for it to finish before calling another one\."]
   [:not-valid-setting #"\"(.*)\" is not a valid battle setting for current mod and map \(use \"!list bSettings\" to list available battle settings\)"]
   [:not-allowed #"\* (.*), you are not allowed to call command \"(.*)\" in current context\."]
   [:invalid #"Invalid command \"(.*)\""]
   [:already-set #"\* Global setting \"(.*)\" is already set to value \"(.*)\""]
   [:force-spec #"Forcing spectator mode for (.*) \[(.*)\] \((.*)\)"]
   [:vote-cancelled #"Vote cancelled by (.*)"]
   [:bar-manager #"BarManager\|(.*)"]
   [:mute #"In-game mute added for (.*) by (.*) \((.*)\)"]
   [:cancelling-vote #"\* Cancelling \"(.*)\" vote \((.)\)"]
   [:game-starting-cancel #"Game starting, cancelling \"(.*)\" vote"]
   [:stopped #"Server stopped \((.*)\)"]
   [:award #" (.*) award:\s+(.*)\s+\((.*)\)(.*)"]
   [:ally-team-won #"Ally team (.*) won! \((.*)\)"]
   [:won #"(.*) won!"]
   [:vote-cancelled-game-launch #"Vote cancelled, launching game\.\.\."]
   [:game-launch #"Launching game\.\.\."]
   [:force-start #"Forcing game start by (.*)"]
   [:random-map #"Automatic random map rotation: next map is \"(.*)\""]
   [:auto-forcing #"Auto-forcing game start \(only already in-game or unsynced spectators are missing\)"]
   [:map-changed #"Map changed by (.*): (.*)"]
   [:no-one-to-ring #"There is no one to ring"]])


(def message-types
  (sort (map first spads-message-types)))


(defn parse-spads-message [text]
  (some
    (fn [[k re]]
      (when-let [parsed (re-find re text)]
        {:text text
         :spads-parsed parsed
         :spads-message-type k}))
    spads-message-types))


(defn parse-command-message [text]
  (when text
    (let [trimmed (string/trim text)]
      (when (string/starts-with? trimmed "!")
        (let [command (subs trimmed 1)
              vote (cond
                     (= command "y") :y
                     (= command "n") :n
                     (= command "b") :b
                     (re-find #"vote\s+y" command) :y
                     (re-find #"vote\s+n" command) :n
                     (re-find #"vote\s+b" command) :b
                     (re-find #"^cv" command) :cv
                     (re-find #"^callvote" command) :cv
                     :else nil)]
          {:command command
           :vote vote})))))

(defn parse-relay-message [text]
  (when text
    (let [trimmed (string/trim text)]
      (when (string/starts-with? trimmed "<")
        (let [[_all username] (re-find #"<([^\s]+)>" trimmed)]
          {:on-behalf-of username})))))
