(ns spring-lobby.util
  (:require
    [com.evocomputing.colors :as colors]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as string]
    java-time
    [spring-lobby.git :as git]
    [taoensso.timbre :as log]
    [taoensso.timbre.appenders.3rd-party.rotor :as rotor])
  (:import
    (java.net URL)
    (java.net URLDecoder URLEncoder)
    (java.nio.charset StandardCharsets)
    (java.time LocalDateTime)
    (java.util TimeZone)
    (java.util.jar Manifest)
    (javafx.scene.paint Color)
    (org.apache.commons.io FileUtils)))


(set! *warn-on-reflection* true)


(def app-name "skylobby")


(def max-messages 200) ; for chat and console

(def minimap-size 512) ; display size in UI
(def start-pos-r 10.0) ; start position radius

(def minimap-types
  ["minimap" "metalmap" "heightmap"])


(defn manifest-attributes [url]
  (-> (str "jar:" url "!/META-INF/MANIFEST.MF")
      URL. .openStream Manifest. .getMainAttributes))
      ;(.getValue "Build-Number")))

; https://stackoverflow.com/a/16431226/984393
(defn manifest-version []
  (try
    (when-let [clazz (Class/forName "spring_lobby")]
      (log/debug "Discovered class" clazz)
      (when-let [loc (-> (.getProtectionDomain clazz) .getCodeSource .getLocation)]
        (log/debug "Discovered location" loc)
        (-> (str "jar:" loc "!/META-INF/MANIFEST.MF")
            URL. .openStream Manifest. .getMainAttributes
            (.getValue "Build-Number"))))
    (catch Exception e
      (log/debug e "Unable to read version from manifest"))))

(defn short-git-commit [git-commit-id]
  (when-not (string/blank? git-commit-id)
    (subs git-commit-id 0 (min 7 (count git-commit-id)))))

(defn app-version []
  (or (manifest-version)
      (try
        (str "git:" (short-git-commit (git/tag-or-latest-id (io/file "."))))
        (catch Exception e
          (log/debug e "Error getting git version")))
      (try
        (slurp (io/resource (str app-name ".version")))
        (catch Exception e
          (log/debug e "Error getting version from file")))
      "UNKNOWN"))


; https://stackoverflow.com/a/17328219/984393
(defn deep-merge [& ms]
  (apply
    merge-with
    (fn [x y]
      (cond (map? y) (deep-merge x y)
            (vector? y) (concat x y)
            :else y))
    ms))

(defn to-number
  "Returns a nilable number from the given number or reads it from the given string."
  [string-or-number]
  (cond
    (number? string-or-number)
    string-or-number
    (boolean? string-or-number)
    (if string-or-number 1 0)
    (string? string-or-number)
    (edn/read-string string-or-number)
    :else
    nil))

(defn to-bool [v]
  (cond
    (boolean? v) v
    (number? v) (not (zero? v))
    (string? v) (recur (to-number v))
    :else
    (boolean v)))


(defn curr-millis
  "Returns (System/currentTimeMillis)."
  []
  (System/currentTimeMillis))


(defn random-color
  []
  (long (rand (* 255 255 255))))


(defn format-bytes
  "Returns a string of the given byte count in human readable format."
  [n]
  (when (number? n)
    (FileUtils/byteCountToDisplaySize (long n))))


(defn decode [^String s]
  (URLDecoder/decode s (.name (StandardCharsets/UTF_8))))


(defn log-to-file [log-path]
  (println "Setting up log to" log-path)
  (log/merge-config!
    {:min-level :info
     :appenders
     {:rotor (rotor/rotor-appender
               {:path log-path
                :max-size 100000000
                :backlog 9
                :stacktrace-fonts {}})}}))


(defmacro try-log
  "Log message, try an operation, log any Exceptions with message, and log and rethrow Throwables."
  [message & body]
  `(try
     (log/info (str "Start " ~message))
     ~@body
     (catch Exception e#
       (log/error e# (str "Exception " ~message)))
     (catch Throwable t#
       (log/error t# (str "Error " ~message))
       (throw t#))))

; https://clojuredocs.org/clojure.core/slurp
(defn slurp-bytes
  "Slurp the bytes from a slurpable thing"
  [x]
  (with-open [out (java.io.ByteArrayOutputStream.)]
    (io/copy (io/input-stream x) out)
    (.toByteArray out)))

(defn battle-channel-name? [channel-name]
  (and channel-name
       (string/starts-with? channel-name "__battle__")))

(defn user-channel-name? [channel-name]
  (and channel-name
       (string/starts-with? channel-name "@")))

(defn user-channel [username]
  (str "@" username))


(defn postprocess-byar-units-en [language-units-en]
  (->> language-units-en
       :en
       :units
       :factions
       (map-indexed
         (fn [i [_k v]]
           [(str i) {:name v}]))
       (into {})))

; https://github.com/cemerick/url/blob/master/src/cemerick/url.cljx
(defn url-encode
  [string]
  (some-> string str (URLEncoder/encode "UTF-8") (.replace "+" "%20")))


(defn update-console-log [state-atom source client message]
  (swap! state-atom
    (fn [state]
      (let [server-url (->> state  ; TODO fix hack
                            :by-server
                            (filter #(identical? (-> % second :client) client))
                            first
                            first)]
        (if server-url
          (update-in state [:by-server server-url :console-log]
            (fn [console-log]
              (conj console-log {:timestamp (curr-millis)
                                 :source source
                                 :message message})))
          (do
            (log/warn "No server-url found for message:" message)
            state))))))

(defn server-key [{:keys [server-url]}]
  (str server-url)) ; TODO username too

(defn append-console-log [state-atom server-key source message]
  (swap! state-atom
    (fn [state]
      (update-in state [:by-server server-key :console-log]
        (fn [console-log]
          (conj console-log {:timestamp (curr-millis)
                             :source source
                             :message message}))))))

(defn update-chat-messages-fn
  ([username message]
   (update-chat-messages-fn username message false))
  ([username message ex]
   (fn [messages]
     (conj messages {:text message
                     :timestamp (curr-millis)
                     :username username
                     :ex ex}))))

(defn parse-skill [skill]
  (cond
    (number? skill)
    skill
    (string? skill)
    (let [[_all n] (re-find #"~?#?([\d\.]+)#?" skill)]
      (try
        (Double/parseDouble n)
        (catch Exception e
          (log/warn e "Error parsing skill" skill))))
    :else nil))

(defn nickname [{:keys [ai-name bot-name owner username]}]
  (if bot-name
    (str bot-name " (" ai-name ", " owner ")")
    (str username)))

(defn spring-color-to-javafx
  "Returns the rgb int color represention for the given Spring bgr int color."
  [spring-color]
  (let [spring-color-int (if spring-color (to-number spring-color) 0)
        [r g b _a] (:rgba (colors/create-color spring-color-int))
        reversed (colors/create-color
                   {:r b
                    :g g
                    :b r})]
    (Color/web (format "#%06x" (colors/rgb-int reversed)))))

(defn javafx-color-to-spring
  "Returns the spring bgr int color format from a javafx color."
  [^javafx.scene.paint.Color color]
  (colors/rgba-int
    (colors/create-color
      {:r (Math/round (* 255 (.getBlue color)))  ; switch blue to red
       :g (Math/round (* 255 (.getGreen color)))
       :b (Math/round (* 255 (.getRed color)))   ; switch red to blue
       :a 0})))

(defn download-progress
  [{:keys [current total]}]
  (if (and current total)
    (str (format-bytes current)
         " / "
         (format-bytes total))
    "Downloading..."))

(defn- parse-mod-name-git [mod-name]
  (or (re-find #"(.+)\s([0-9a-f]+)$" mod-name)
      (re-find #"(.+)\sgit:([0-9a-f]+)$" mod-name)
      (re-find #"(.+)\s(\$VERSION)$" mod-name)))

(defn mod-git-ref
  "Returns the git ref from the given mod name, or nil if it does not parse."
  [mod-name]
  (when-let [[_all _mod-prefix git] (parse-mod-name-git mod-name)]
    git))

(defn format-hours
  ([timestamp-millis]
   (format-hours (.toZoneId (TimeZone/getDefault)) timestamp-millis))
  ([time-zone-id timestamp-millis]
   (java-time/format "HH:mm:ss" (LocalDateTime/ofInstant
                                  (java-time/instant timestamp-millis)
                                  time-zone-id))))

(defn format-datetime
  ([timestamp-millis]
   (format-datetime (.toZoneId (TimeZone/getDefault)) timestamp-millis))
  ([time-zone-id timestamp-millis]
   (java-time/format "yyyyMMdd-HHmmss" (LocalDateTime/ofInstant
                                         (java-time/instant timestamp-millis)
                                         time-zone-id))))

(defn non-battle-channels
  [channels]
  (->> channels
       (remove (comp string/blank? :channel-name))
       (remove (comp battle-channel-name? :channel-name))))


(defn spring-script-color-to-int [rgbcolor]
  (let [[r g b] (string/split rgbcolor #"\s")
        color (colors/create-color
                :r (int (* 255 (Double/parseDouble b)))
                :g (int (* 255 (Double/parseDouble g)))
                :b (int (* 255 (Double/parseDouble r))))]
    (colors/rgba-int color)))
