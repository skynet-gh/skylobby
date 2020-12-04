(ns spring-lobby.spring.script
  (:require
    [clojail.core :as clojail]
    [clojure.edn :as edn]
    [clojure.string :as string]
    [instaparse.core :as instaparse]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)


(defn tag=
  ([definition value1]
   (tag= definition value1 (:def definition)))
  ([definition value1 value2]
   (case (:type definition)
     "number" (= (u/to-number value1) (u/to-number value2))
     "bool" (= (u/to-bool value1) (u/to-bool value2))
     ; else
     (= value1 value2))))


(defn flatten-scripttags
  ([scripttags]
   (flatten-scripttags nil scripttags))
  ([prefix scripttags]
   (mapcat
     (fn [[k v]]
       (let [prefix (if prefix
                      (str prefix "/" (name k))
                      (name k))]
         (cond
           (or (and (seqable? v) (not (seq v)))
               (string/blank? (str v)))
           []
           (map? v)
           (flatten-scripttags prefix v)
           :else
           [(str prefix "=" v)])))
     scripttags)))

(defn format-scripttags [scripttags-data]
  (string/join
    "\t"
    (flatten-scripttags scripttags-data)))


(defn parse-scripttag-key [scripttag-key]
  (map keyword (string/split scripttag-key #"/")))

(defn parse-scripttags [raw-scripttags]
  (->> (string/split raw-scripttags #"\t")
       (remove string/blank?)
       (map
         (fn [raw-scripttag]
           (let [[_all ks v] (re-find #"([^\s]+)=(.*)" raw-scripttag)
                 kws (parse-scripttag-key ks)]
             (assoc-in {} kws v))))
       (apply u/deep-merge)))


(def script-grammar
 (instaparse/parser
   "config = block*
     block = <comment*>
             tag <comment*>
             <'{'>
               ( block | field | <comment> )*
             <'}'>
             <comment*>
     tag = <'['> #'[\\w\\s]+' <']'>
     field = <#'\\s+'?> #'\\w+' <#'\\s+'?> <'='> <#'\\s*'> #'[^;]*' <';'> <#'.*'>?
     comment = '//' #'.*'"
   :auto-whitespace :standard))

(declare parse-fields-or-blocks)

(defn parse-number
  [v]
  (try
    (if-let [e (edn/read-string v)] ; TODO clean
       (if (number? e)
         e
         v)
       v)
    (catch Exception _e
      v)))

(defn parse-field-or-block [field-or-block]
  (let [kind (first field-or-block)]
    (case kind
      :field
      (let [[_kind k v] field-or-block]
        [(keyword (string/lower-case k))
         (parse-number v)])
      :block
      (let [[_block [_tag tag]] field-or-block
            block (nthrest field-or-block 2)]
        [(keyword (string/lower-case tag))
         (parse-fields-or-blocks block)]))))

(defn parse-fields-or-blocks
  [fields-or-blocks]
  (into (sorted-map)
    (map parse-field-or-block fields-or-blocks)))

(defn postprocess [script-parsed-raw]
  (let [blocks (rest script-parsed-raw)]
    (parse-fields-or-blocks blocks)))

; https://stackoverflow.com/a/62915361/984393
(defn remove-nonprintable [s]
  (string/replace s #"[\p{C}&&^(\S)]" ""))

(defn parse-script
  "Returns the parsed data representation of a spring config."
  [script-txt]
  (clojail/thunk-timeout
    #(let [cleaned (remove-nonprintable script-txt)
           parsed (instaparse/parse script-grammar cleaned)]
       (if (instaparse/failure? parsed)
         (do
           (log/debug parsed)
           (throw (ex-info "Failed to parse" {:pr-script-txt (pr-str cleaned)})))
         (postprocess parsed)))
    2000))
