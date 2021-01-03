(ns spring-lobby.git
  "Utils for working with git resources."
  (:require
    [clj-jgit.porcelain :as git]
    [taoensso.timbre :as log])
  (:import
    (java.io File)
    (org.eclipse.jgit.api Git)
    (org.eclipse.jgit.lib ObjectId ProgressMonitor Ref Repository)
    (org.eclipse.jgit.revwalk RevCommit)))


(set! *warn-on-reflection* true)


(def ba-repo-url
  "https://github.com/Balanced-Annihilation/Balanced-Annihilation.git")
(def bar-repo-url
  "https://github.com/beyond-all-reason/Beyond-All-Reason.git")
(def known-mod-repos
  [ba-repo-url
   bar-repo-url])

(defn- repo-latest-id [repo]
  (when-let [^RevCommit first-log (first (git/git-log repo))]
    (ObjectId/toString (.getId first-log))))

(defn latest-id [^File f]
  (with-open [repo (git/load-repo f)]
    (repo-latest-id repo)))

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

(defn fetch [^File f]
  (with-open [repo (git/load-repo f)]
    (git/git-fetch repo)))

(defn reset-hard [^File f commit-id]
  (with-open [repo (git/load-repo f)]
    (git/git-reset repo commit-id :hard)))

(defn- repo-tag-list [^Git repo]
  (for [^Ref tag (.call (.tagList repo))]
    (let [tag-name (.getName tag)]
      {:tag-name tag-name
       :short-tag-name (when tag-name (Repository/shortenRefName tag-name))
       :object-id (ObjectId/toString (.getObjectId tag))})))

(defn tag-list [^File f]
  (with-open [repo (git/load-repo f)]
    (repo-tag-list repo)))

; https://stackoverflow.com/a/14933815/984393
(defn tag-or-latest-id
  "Returns the current tag, if any. If there is no tag, returns the commit id string."
  [^File f]
  (with-open [repo (git/load-repo f)]
    (when-let [latest-id (repo-latest-id repo)]
      (let [tag-names-by-id (->> repo
                                 repo-tag-list
                                 (map (juxt :object-id :short-tag-name))
                                 (into {}))]
        (or (get tag-names-by-id latest-id)
            latest-id)))))
