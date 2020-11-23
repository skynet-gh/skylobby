(ns spring-lobby.http
  (:require
    [crouton.html :as html]))


(def springrts-buildbot-root
  "https://springrts.com/dl/buildbot/default/")

(def springfiles-maps
  "http://api.springfiles.com/files/maps/")

(def springfightclub-root
  "https://www.springfightclub.com/data/")


(defn- by-tag [element tag]
  (->> element
       :content
       (filter (comp #{tag} :tag))
       first))

(defn- in-tags [element tags]
  (let [tag (first tags)
        it (by-tag element tag)]
    (if-let [r (seq (rest tags))]
      (recur it r)
      it)))

(defn links [parsed-html]
  (let [rows (-> parsed-html
                 (in-tags [:body :table :tbody])
                 :content)]
    (->> rows
         (filter :content)
         (map
           (fn [row]
             (some
               #(by-tag % :a)
               (:content row))))
         (filter some?)
         (map (comp :href :attrs)))))


(def parsed-springrts-buildbot-root
  (html/parse springrts-buildbot-root))

(def parsed-springfiles-maps
  (html/parse springfiles-maps))

(def parsed-springfightclub-root
  (html/parse springfightclub-root))


#_
(links parsed-springrts-buildbot-root)
#_
(links parsed-springfiles-maps)
#_
(links parsed-springfightclub-root)
