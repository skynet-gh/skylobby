(ns skylobby.spring
  (:require
    [clojure.java.io :as io]
    [skylobby.fs :as fs]
    [taoensso.timbre :as log]))


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
