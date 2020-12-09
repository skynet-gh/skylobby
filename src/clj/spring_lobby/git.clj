(ns spring-lobby.git
  (:require
    [clj-jgit.porcelain :as git]
    [clj-jgit.querying :as querying]
    [taoensso.timbre :as log]))


(def known-mod-repos
  ["https://github.com/Balanced-Annihilation/Balanced-Annihilation.git"
   "https://github.com/beyond-all-reason/Beyond-All-Reason.git"])

(defn latest-id [^java.io.File f]
  (let [repo (git/load-repo f)]
    (->> (git/git-log repo :max-count 1)
         first
         :id
         (querying/commit-info repo)
         :id)))

(defn clone-repo [repo-url dest]
  (let [repo (git/git-clone repo-url :dir dest)]
    (log/info "Cloned" repo-url
              (pr-str (latest-id repo)))
    repo))

#_
(clone-game (first known-mod-repos))
#_
(clone-game (second known-mod-repos))
#_
(latest-id (io/file (fs/spring-root) "games" "Beyond-All-Reason"))
