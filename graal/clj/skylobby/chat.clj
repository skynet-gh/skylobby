(ns skylobby.chat
  (:require
    [clojure.string :as string]))


(set! *warn-on-reflection* true)


(defn ignore-users-set [ignore-users]
  (->> ignore-users
       (filter second)
       (map first)
       set))

(defn hide-spads-set [hide-spads-messages]
  (->> hide-spads-messages
       (filter second)
       (map first)
       set))

(defn visible-message?
  [{:keys [
           hide-barmanager-messages
           hide-joinas-spec
           hide-spads-set
           hide-vote-messages
           ignore-users-set]}
   {:keys [message-type relay spads text username vote]}]
  (not
    (or (and
          username
          (contains? ignore-users-set username))
        (and (= "Coordinator" username)
             (string/starts-with? text "skylobby"))
        (and
          (:on-behalf-of relay)
          (contains? ignore-users-set (:on-behalf-of relay)))
        (contains? hide-spads-set (:spads-message-type spads))
        (and hide-vote-messages
             (:vote vote))
        (and hide-joinas-spec
             (= "joinas spec"
                (:command vote)))
        (and hide-barmanager-messages
             (and (= :ex message-type)
                  text
                  (string/starts-with? text "* BarManager|"))))))
