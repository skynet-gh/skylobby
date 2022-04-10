(ns skylobby.cli.util)


(set! *warn-on-reflection* true)


(defn print-and-exit [exit-code & messages]
  (doseq [message messages]
    (println message))
  (shutdown-agents)
  (System/exit exit-code))
