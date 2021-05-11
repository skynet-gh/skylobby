(ns spring-lobby.ui-main
  (:require
    [cljfx.api :as fx]
    [cljfx.css :as css]
    clojure.core.async
    [clojure.string :as string]
    [clojure.tools.cli :as cli]
    skylobby.fx
    [skylobby.fx.replay :as fx.replay]
    [skylobby.fx.root :as fx.root]
    spring-lobby
    [spring-lobby.fs :as fs]
    [spring-lobby.fs.sdfz :as sdfz]
    [spring-lobby.replays :as replays]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (javafx.application Platform))
  (:gen-class))


(def cli-options
  [[nil "--chat-channel CHANNEL_NAME" "Add a default chat channel to connect to"
    :assoc-fn (fn [m k v]
                (update m k conj v))]
   [nil "--css-file CSS_FILE" "Use the given file for CSS style"]
   [nil "--css-preset CSS_PRESET_NAME" "Use the given CSS preset"]
   [nil "--filter-battles FILTER_BATTLES" "Set the initial battles filter string"]
   [nil "--filter-users FILTER_USERS" "Set the initial users filter string"]
   [nil "--no-update-check" "Diable skylobby self update check"]
   [nil "--replay-source REPLAY_SOURCE" "Replace default replay sources with one or more overrides"
    :assoc-fn (fn [m k v]
                (update m k conj v))]
   [nil "--skylobby-root SKYLOBBY_ROOT" "Set the config and log dir for skylobby"]
   [nil "--spring-root SPRING_ROOT" "Set the spring-root config to the given directory"]
   [nil "--server-url SERVER_URL" "Set the selected server config by url"]
   [nil "--window-maximized" "Start with the main window maximized"]])


(defn -main [& args]
  (let [{:keys [arguments options]} (cli/parse-opts args cli-options)
        first-arg-as-file (some-> arguments first fs/file)
        first-arg-filename (fs/filename first-arg-as-file)
        opening-replay? (and (not (string/blank? first-arg-filename))
                             (string/ends-with? first-arg-filename ".sdfz")
                             (fs/exists? first-arg-as-file))]
    (try
      (when (:no-update-check options)
        (alter-var-root #'spring-lobby/disable-update-check (constantly true)))
      (when-let [app-root-override (:skylobby-root options)]
        (alter-var-root #'fs/app-root-override (constantly app-root-override)))
      (when-let [replay-sources (seq (:replay-source options))]
        (let [replay-sources-override (map
                                        (fn [source]
                                          {:replay-source-name ""
                                           :file (fs/file source)
                                           :builtin true})
                                        replay-sources)]
          (log/info "Replacing replay sources with" (pr-str replay-sources-override))
          (alter-var-root #'fs/replay-sources-override (constantly replay-sources-override))))
      (u/log-to-file (fs/canonical-path (fs/config-file (str u/app-name ".log"))))
      (let [before (u/curr-millis)]
        (log/info "Main start")
        (Platform/setImplicitExit true)
        (log/info "Set JavaFX implicit exit")
        (let [before-state (u/curr-millis)
              _ (log/info "Loading initial state")
              initial-state (spring-lobby/initial-state)
              state (merge
                      initial-state
                      {:standalone true}
                      (when (contains? options :spring-root)
                        {:spring-isolation-dir (fs/file (:spring-root options))})
                      (when (contains? options :filter-battles)
                        {:filter-battles (:filter-battles options)})
                      (when (contains? options :filter-users)
                        {:filter-users (:filter-users options)})
                      (when (contains? options :window-maximized)
                        {:window-maximized true})
                      (when (contains? options :server-url)
                        (let [server (->> initial-state
                                          :servers
                                          (filter (comp #{(:server-url options)} first))
                                          first)
                              {:keys [password username]} (->> initial-state
                                                               :logins
                                                               (filter (comp #{(:server-url options)} first))
                                                               first
                                                               second)]
                          {:server server
                           :password password
                           :username username}))
                      (when (contains? options :chat-channel)
                        {:global-chat-channels
                         (map
                           (juxt identity (constantly {}))
                           (:chat-channel options))}))]
          (log/info "Loaded initial state in" (- (u/curr-millis) before-state) "ms")
          (reset! spring-lobby/*state state)
          (let [previous-css (css/register :skylobby.fx/current
                                (or (:css state)
                                    skylobby.fx/default-style-data))
                css (cond
                      (:css-file options)
                      {:cljfx.css/url (some-> options :css-file fs/file .toURI .toURL)}
                      (:css-preset options)
                      (css/register :skylobby.fx/current
                        (get skylobby.fx/style-presets (some-> options :css-preset string/lower-case)))
                      :else previous-css)]
            (swap! spring-lobby/*state assoc :css css)))
        (cond
          (= "skyreplays" (first arguments))
          (do
            (log/info "Starting skyreplays")
            (swap! spring-lobby/*state assoc :show-replays true :standalone true)
            (fs/init-7z!)
            (replays/create-renderer)
            (spring-lobby/init-async spring-lobby/*state))
          opening-replay?
          (let [
                _ (fs/init-7z!)
                selected-replay (sdfz/parse-replay first-arg-as-file)]
            (log/info "Opening replay view")
            (swap! spring-lobby/*state
                   assoc
                   :selected-replay selected-replay
                   :selected-replay-file first-arg-as-file)
            (spring-lobby/replay-map-and-mod-details-watcher nil spring-lobby/*state nil @spring-lobby/*state)
            (let [r (fx/create-renderer
                      :middleware (fx/wrap-map-desc
                                    (fn [state]
                                      {:fx/type fx.replay/standalone-replay-window
                                       :state state}))
                      :opts {:fx.opt/map-event-handler spring-lobby/event-handler})]
              (log/info "Mounting renderer")
              (fx/mount-renderer spring-lobby/*state r))
            (spring-lobby/standalone-replay-init spring-lobby/*state)
            (log/info "Main finished in" (- (u/curr-millis) before) "ms"))
          :else
          (do
            (future
              (log/info "Start 7Zip init, async")
              (fs/init-7z!)
              (log/info "Finished 7Zip init"))
            (log/info "Creating renderer")
            (let [r (fx/create-renderer
                      :middleware (fx/wrap-map-desc
                                    (fn [state]
                                      {:fx/type fx.root/root-view
                                       :state state}))
                      :opts {:fx.opt/map-event-handler spring-lobby/event-handler})]
              (log/info "Mounting renderer")
              (fx/mount-renderer spring-lobby/*state r))
            (spring-lobby/init-async spring-lobby/*state)
            (spring-lobby/auto-connect-servers spring-lobby/*state)
            (log/info "Main finished in" (- (u/curr-millis) before) "ms"))))
      (catch Throwable t
        (let [st (with-out-str (.printStackTrace t))]
          (println st)
          (spit "skylobby-fatal-error.txt" st))
        (log/error t "Fatal error")
        (System/exit -1)))))
