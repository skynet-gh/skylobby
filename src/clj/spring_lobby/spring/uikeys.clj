(ns spring-lobby.spring.uikeys
  (:require
    [clojail.core :as clojail]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [instaparse.core :as instaparse]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)


(def uikeys-grammar
  (instaparse/parser
    "uikeys = line*
     <line> = (bind | notbind | #'\\s*')
     bind = <'bind'> <#'\\s+'> #'[^\\s]+' <#'\\s+'> #'[^/\\r\\n]+' comment?
     notbind = !'bind' #'\\w+' #'\\s+' #'.*'
     comment = <'//'> #'.*'"
    :auto-whitespace :standard))

(defn postprocess [parsed-uikeys]
  (->> parsed-uikeys
       (filter coll?)
       (filter (comp #{:bind} first))
       (map
         (fn [[_kind key-combo action [_comment comment-str]]]
           {:bind-key key-combo
            :bind-action (string/trim action)
            :bind-comment comment-str}))
       (filter some?)))


(defn parse-uikeys
  ([]
   (parse-uikeys (slurp (io/resource "uikeys.tmp"))))
  ([uikeys-raw]
   (clojail/thunk-timeout
     #(let [parsed (instaparse/parse uikeys-grammar uikeys-raw)]
        (if (instaparse/failure? parsed)
          (do
            (log/debug parsed)
            (throw (ex-info "Failed to parse" {:uikeys-pr-str (pr-str uikeys-raw)})))
          (postprocess parsed)))
     2000)))

(defn parse-bind-keys [bind-keys-str]
  (let [combos (string/split bind-keys-str #"(?<!,),(?=.)")]
    (map
      #(when %
         (string/split % #"(?<!\+)\+(?=.)"))
      combos)))
