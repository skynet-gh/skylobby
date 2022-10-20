(ns skylobby.git
  "Utils for working with git resources."
  (:require
    [clj-jgit.porcelain :as git]
    [clojure.string :as string])
  (:import
    (java.io File)
    (org.eclipse.jgit.api Git)
    (org.eclipse.jgit.lib ObjectId Ref Repository)
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
    (ObjectId/toString (:id first-log))))

(defn latest-id [^File f]
  (with-open [repo (git/load-repo f)]
    (repo-latest-id repo)))

(defn fetch [^File f]
  (with-open [repo (git/load-repo f)]
    (git/git-fetch repo)))

(defn reset-hard [^File f commit-id]
  (with-open [repo (git/load-repo f)]
    (git/git-reset repo :ref commit-id :mode :hard)))

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

(defn short-git-commit [git-commit-id]
  (when-not (string/blank? git-commit-id)
    (subs git-commit-id 0 (min 7 (count git-commit-id)))))
