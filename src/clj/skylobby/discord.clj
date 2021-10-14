(ns skylobby.discord
  (:require
    [cheshire.core :as json]
    [clj-http.client :as http]
    [clojure.string :as string]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)


(def base-url "https://discord.com/api/webhooks/")

(def cooldown 180000) ; 3 minutes

(def tokens
  {"898249621527220235" "czlXYXJqSlpEY1VINU1UenpjMVpFNGJ1cGVBV1N5XzBXbXpPR2Z2M3pwQmJKOWdzbTdNbHFGYlJoMXJ6b296Y0x0Q0Y="})


(defn channel-to-promote [{:keys [mod-name]}]
  (when (and mod-name
             (string/starts-with? mod-name "Balanced Annihilation"))
    "898249621527220235"))

(defn promote-battle [channel-id {:keys [battle-title map-name mod-name team-counts]}]
  (let [
        url (str base-url channel-id "/" (u/base64-decode (get tokens channel-id)))
        total-players (reduce (fnil + 0 0) 0 (flatten team-counts))
        body {:content (str total-players " looking for more players in " battle-title "\n"
                            mod-name " on " map-name)
              :username "skylobby"}
        _ (log/info "Posting Discord promotion to" url ":" body)
        response
        (http/post url
          {:body (json/generate-string body)
           :content-type :json
           :accept :json
           :as :json})]
    (log/info "Response from Discord:" (:body response))))
