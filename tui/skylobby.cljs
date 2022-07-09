(ns skylobby
  (:require
    [clojure.edn :as edn]
    [clojure.string :as string]
    ["ink" :refer [Newline render Text]]
    ["inquirer$default" :as inquirer]
    [reagent.core :as r]
    ["shelljs$default" :as sh]))


(defonce *state
  (r/atom
    {:errors []}))


(defn ui []
  (into
    [:> Text
     [:> Text {:color "green"} "skylobby init"]
     [:> Newline]
     (when-let [port (:port @*state)]
       [:> Text "port: " port])]
    (concat
      (mapv
        (fn [error]
          [:> Text {:color "red"} (str error)])
        (:errors @*state))
      (mapv
        (fn [[server-url]]
          [:> Text (str server-url)])
        (:servers @*state)))))



(render (r/as-element [ui]))


(def windows?
  (-> js/process.platform
      string/lower-case
      (string/starts-with? "win")))

(def command
  (str "skylobby" (when windows? ".exe") " --quiet tui"))

(let [child (sh/exec command #js {:async true :silent true}
              (fn [exit-code _ _]
                (println "skylobby exited with code" exit-code)))]
  (.on
    (.-stdout child)
    "data"
    (fn [raw]
      (let [data (edn/read-string raw)]
        (if (map? data)
          (swap! *state merge data)
          (println "data" (pr-str data))))))
  (.on
    (.-stderr child)
    "data"
    (fn [error]
      (swap! *state update :errors conj error)))
  (.write
    (.-stdin child)
    "servers\r\n"))
