(ns skylobby.spring
  (:require
    [clojure.core.async :as async]
    [clojure.java.io :as io]
    [clojure.string :as string]
    clojure.walk
    [skylobby.fs :as fs]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)


(defn script-txt-inner
  ([kv]
   (script-txt-inner "" kv))
  ([tabs [k v]]
   (str tabs
        (if (map? v)
          (str "[" (name k ) "]\n" tabs "{\n"
               (apply str (map (partial script-txt-inner (str tabs "\t")) (sort-by (comp str first) v)))
               tabs "}\n")
          (str (name k) " = " v ";"))
        "\n")))

; https://springrts.com/wiki/Script.txt
; https://github.com/spring/spring/blob/104.0/doc/StartScriptFormat.txt
; https://github.com/springlobby/springlobby/blob/master/src/spring.cpp#L284-L590
(defn script-txt
  "Given data for a battle, return contents of a script.txt file for Spring."
  ([script-data]
   (apply str (map script-txt-inner (sort-by first (clojure.walk/stringify-keys script-data))))))

(defn get-envp [] nil)

(defn wait-for-spring [^java.lang.Process process state-atom spring-log-state {:keys [infolog-dest]}]
  (let [exit-code (.waitFor process)]
    (log/info "Spring exited with code" exit-code)
    (when (not= 0 exit-code)
      (log/info "Non-zero spring exit, showing info window")
      (let [spring-log @spring-log-state
            archive-not-found (when-let [[_all archive-name _ resolved] (some #(re-find #"errorMsg=\"Dependent archive \"([^\"]*)\" (\(resolved to \"([^\"]*)\"\))? not found\"" %)
                                                                              (map :line spring-log))]
                                {:archive-name archive-name
                                 :resolved-archive-name resolved})]
        (swap! state-atom assoc
               :show-spring-info-window true
               :spring-log spring-log
               :spring-crash-infolog-file infolog-dest
               :spring-crash-archive-not-found archive-not-found)))))

(defn start-game
  [{:keys [engine-dir ^java.io.File spring-root]}]
  (try
    (let [
          engine-file (io/file engine-dir (fs/spring-executable))
          _ (log/info "Engine executable" engine-file)
          _ (fs/set-executable engine-file)
          script-file (io/file spring-root "script.txt")
          script-file-param (fs/wslpath script-file)
          isolation-dir-param (fs/wslpath engine-dir)
          write-dir-param (fs/wslpath spring-root)
          command [(fs/canonical-path engine-file)
                   "--isolation-dir" isolation-dir-param
                   "--write-dir" write-dir-param
                   script-file-param]
          runtime (Runtime/getRuntime)
          _ (log/info "Running '" command "'")
          _ (log/info "Copy paste Spring command: '" (with-out-str (println (string/join " " command))) "'")
          ^"[Ljava.lang.String;" cmdarray (into-array String command)
          ^"[Ljava.lang.String;" envp (into-array String [])
          process (.exec runtime cmdarray envp spring-root)]
      (future
        (with-open [^java.io.BufferedReader reader (io/reader (.getInputStream process))]
          (loop []
            (if-let [line (.readLine reader)]
              (do
                (log/info "(spring out)" line)
                (recur))
              (log/info "Spring stdout stream closed")))))
      (future
        (with-open [^java.io.BufferedReader reader (io/reader (.getErrorStream process))]
          (loop []
            (if-let [line (.readLine reader)]
              (do
                (log/info "(spring err)" line)
                (recur))
              (log/info "Spring stderr stream closed")))))
      (try
        (.waitFor process)
        (catch Exception e
          (log/error e "Error waiting for Spring to close"))
        (catch Throwable t
          (log/error t "Fatal error waiting for Spring to close")
          (throw t))))
    (catch Exception e
      (log/error e "Error starting game"))))


(defn watch-replay
  [state-atom {:keys [engine-version engines replay-file ^java.io.File spring-isolation-dir]}]
  (try
    (log/info "Watching replay" replay-file)
    (swap! state-atom
      (fn [state]
        (-> state
            (assoc-in [:replays-watched (fs/canonical-path replay-file)] true)
            (assoc-in [:spring-running :replay :replay] true))))
    (let [engine-dir (some->> engines
                              (filter (comp #{engine-version} :engine-version))
                              first
                              :file)
          engine-file (io/file engine-dir (fs/spring-executable))
          _ (log/info "Engine executable" engine-file)
          _ (fs/set-executable engine-file)
          replay-file-param (fs/wslpath replay-file)
          isolation-dir-param (fs/wslpath engine-dir)
          write-dir-param (fs/wslpath spring-isolation-dir)
          command [(fs/canonical-path engine-file)
                   "--isolation-dir" isolation-dir-param
                   "--write-dir" write-dir-param
                   replay-file-param]
          runtime (Runtime/getRuntime)
          spring-log-state (atom [])]
      (log/info "Running '" command "'")
      (let [^"[Ljava.lang.String;" cmdarray (into-array String command)
            ^"[Ljava.lang.String;" envp (get-envp)
            process (.exec runtime cmdarray envp spring-isolation-dir)
            pid (.pid process)]
        (async/thread
          (with-open [^java.io.BufferedReader reader (io/reader (.getInputStream process))]
            (loop []
              (if-let [line (.readLine reader)]
                (do
                  (log/info "(spring" pid "out)" line)
                  (swap! spring-log-state conj {:stream :out :line line})
                  (recur))
                (log/info "Spring stdout stream closed")))))
        (async/thread
          (with-open [^java.io.BufferedReader reader (io/reader (.getErrorStream process))]
            (loop []
              (if-let [line (.readLine reader)]
                (do
                  (log/info "(spring" pid "err)" line)
                  (swap! spring-log-state conj {:stream :err :line line})
                  (recur))
                (log/info "Spring stderr stream closed")))))
        (wait-for-spring process state-atom spring-log-state nil)))
    (catch Exception e
      (log/error e "Error starting replay" replay-file))
    (finally
      (swap! state-atom assoc-in [:spring-running :replay :replay] false))))
