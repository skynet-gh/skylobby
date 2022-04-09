(ns skylobby.download
  (:require
    [clj-http.client :as clj-http]
    [clojure.string :as string]
    [skylobby.fs :as fs]
    [skylobby.http :as http]
    [skylobby.resource :as resource]
    [skylobby.task :as task]
    [skylobby.util :as u]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)


(defprotocol DownloadIndex
  (all-downloadables [this])
  (downloadable-by-url [this url])
  (downloadables-by-source [this download-source])
  (update-downloadable [this downloadable])
  (update-downloadables [this downloadables]))


(def downloadable-update-cooldown
  (* 1000 60 60 24)) ; 1 day


(defn update-download-source
  [state-atom {:keys [resources-fn url download-source-name] :as source}]
  (log/info "Getting resources for possible download from" download-source-name "at" url)
  (let [now (u/curr-millis)
        last-updated (or (-> state-atom deref :downloadables-last-updated (get url)) 0)] ; TODO remove deref
    (if (or (< downloadable-update-cooldown (- now last-updated))
            (:force source))
      (let [{:keys [db use-db-for-downloadables]} (swap! state-atom assoc-in [:downloadables-last-updated url] now)
            _ (log/info "Updating downloadables from" url)
            downloadables (resources-fn source)
            downloadables-by-url (->> downloadables
                                      (map (juxt :download-url identity))
                                      (into {}))
            all-download-source-names (set (keys http/download-sources-by-name))]
        (log/info "Found downloadables from" download-source-name "at" url
                  (frequencies (map :resource-type downloadables)))
        (if (and db use-db-for-downloadables)
          (do
            (update-downloadables db downloadables)
            (swap! state-atom dissoc :downloadables-by-url))
          (swap! state-atom update :downloadables-by-url
            (fn [old]
              (let [invalid-download-source (remove (comp all-download-source-names :download-source-name second) old)]
                (when (seq invalid-download-source)
                  (log/warn "Deleted" (count invalid-download-source) "downloads from invalid sources"))
                (merge
                  (->> old
                       (remove (comp #{download-source-name} :download-source-name second))
                       (filter (comp all-download-source-names :download-source-name second))
                       (into {}))
                  downloadables-by-url)))))
        (u/update-cooldown state-atom [:download-source download-source-name])
        downloadables-by-url)
      (log/info "Too soon to check downloads from" url))))


(defn search-springfiles
  "Search springfiles.com for the given resource name, returns a string mirror url for the resource,
  or nil if not found."
  [{:keys [category springname]}]
  (log/info "Searching springfiles for" springname)
  (let [result (->> (clj-http/get "https://springfiles.springrts.com/json.php"
                      {:query-params
                       (merge
                         {:springname springname
                          :nosensitive "on"
                          :category (or category "**")})
                       :as :json})
                    :body
                    first)]
    (log/info "First result for" springname "search on springfiles:" result)
    (when-let [mirrors (->> result :mirrors (filter some?) (remove #(string/includes? % "spring1.admin-box.com")) seq)]
      {:filename (:filename result)
       :mirrors mirrors})))

(defn download-http-resource
  [state-atom {:keys [dest downloadable spring-isolation-dir]}]
  (log/info "Request to download" downloadable)
  (future
    (try
      (let [url (:download-url downloadable)
            dest (or dest (resource/resource-dest spring-isolation-dir downloadable))
            temp-dest (fs/download-file (str (hash (str url)) "-" (fs/filename dest)))]
        (log/info "Downloading to temp file" temp-dest "then moving to" dest)
        (http/download-file state-atom url temp-dest)
        (log/info "Moving temp download file" temp-dest "into place at" dest)
        (fs/move temp-dest dest))
      (case (:resource-type downloadable)
        :spring-lobby/map
        (task/add-task! state-atom
          {:spring-lobby/task-type :spring-lobby/refresh-maps
           :spring-root spring-isolation-dir
           :priorities [dest]})
        :spring-lobby/mod
        (task/add-task! state-atom
          {:spring-lobby/task-type :spring-lobby/refresh-mods
           :spring-root spring-isolation-dir
           :priorities [dest]})
        :spring-lobby/engine
        (task/add-task! state-atom
          {:spring-lobby/task-type :spring-lobby/refresh-engines
           :spring-root spring-isolation-dir
           :priorities [dest]})
        nil)
      (catch Exception e
        (log/error e "Error downloading")))))
