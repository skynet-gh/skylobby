(ns spring-lobby.git
  "Utils for working with git resources."
  (:require
    [clj-jgit.porcelain :as git]
    [clj-jgit.querying :as querying]
    [taoensso.timbre :as log])
  (:import
    (org.eclipse.jgit.lib ProgressMonitor)))


(set! *warn-on-reflection* true)


(def ba-repo-url
  "https://github.com/Balanced-Annihilation/Balanced-Annihilation.git")
(def bar-repo-url
  "https://github.com/beyond-all-reason/Beyond-All-Reason.git")
(def known-mod-repos
  [ba-repo-url
   bar-repo-url])


(defn latest-id [^java.io.File f]
  (with-open [repo (git/load-repo f)]
    (->> (git/git-log repo :max-count 1)
         first
         :id
         (querying/commit-info repo)
         :id)))

(defn clone-repo
  ([repo-url dest {:keys [on-begin-task on-end-task on-start]}]
   (let [repo (git/git-clone repo-url
                             :dir dest
                             :monitor
                             (reify ProgressMonitor
                               (beginTask [this title total-work]
                                 (log/info "beginTask" title total-work)
                                 (when (fn? on-begin-task)
                                   (on-begin-task title total-work)))
                               (endTask [this]
                                 (log/info "endTask")
                                 (when (fn? on-begin-task)
                                   (on-end-task)))
                               (isCancelled [this]
                                 false)
                               (start [this total-tasks]
                                 (log/info "start" total-tasks)
                                 (when (fn? on-start)
                                   (on-start total-tasks)))
                               (update [this completed]
                                 (log/trace "update" completed))))]
     (log/info "Cloned" repo-url
               (pr-str (latest-id repo)))
     repo)))

(defn fetch [^java.io.File f]
  (with-open [repo (git/load-repo f)]
    (git/git-fetch repo)))

(defn reset-hard [^java.io.File f commit-id]
  (with-open [repo (git/load-repo f)]
    (git/git-reset repo :mode :hard :ref commit-id)))
