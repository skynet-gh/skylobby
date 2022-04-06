(ns skylobby.cli.util)


(defn print-and-exit [exit-code & messages]
  (doseq [message messages]
    (println message))
  (shutdown-agents)
  (System/exit exit-code))
