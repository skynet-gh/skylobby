(ns skylobby.spring
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    clojure.walk
    [skylobby.fs :as fs]
    [taoensso.timbre :as log]))


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
