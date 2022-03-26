(ns native-image
  (:require
    [clojure.java.io :as io]
    [clojure.tools.namespace.repl :refer [disable-unload! disable-reload!]]
    clj.native-image
    [clojure.tools.namespace.find :refer [find-namespaces-in-dir]]))


(disable-unload!)
(disable-reload!)


; adapted from https://github.com/taylorwood/clj.native-image/blob/master/src/clj/native_image.clj
; mostly just to put classpath in a file since it's too big for a command on Windows


(defn build [main-ns opts]
  (let [nat-image-path (clj.native-image/native-image-bin-path)
        deps-map (clj.native-image/merged-deps)
        namespaces (mapcat (comp find-namespaces-in-dir io/file) (:paths deps-map))]
    (clj.native-image/prep-compile-path)
    (doseq [ns (distinct (cons main-ns namespaces))]
      (println "Compiling" ns)
      (compile (symbol ns)))
    (let [cp (clj.native-image/native-image-classpath)]
      (spit "native-image-args" (str "-cp " cp)))
    (System/exit
      (clj.native-image/exec-native-image
        nat-image-path
        (concat ["@native-image-args"] opts)
        nil
        main-ns))))

(defn -main [main-ns & args]
  (try
    (build main-ns args)
    (finally
      (shutdown-agents))))
