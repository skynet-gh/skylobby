(ns skylobby.util
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [com.evocomputing.colors :as colors]
    java-time
    [skylobby.spads :as spads]
    [taoensso.timbre :as log]
    [taoensso.timbre.appenders.3rd-party.rotor :as rotor])
  (:import
    (java.lang.management ManagementFactory)
    (java.net InetAddress NetworkInterface ServerSocket URL URLDecoder URLEncoder)
    (java.nio.charset StandardCharsets)
    (java.security MessageDigest)
    (java.time Duration LocalDateTime)
    (java.util Base64 TimeZone)
    (java.util.jar Manifest)
    (java.util.zip CRC32)
    (org.apache.commons.codec.binary Hex)
    (org.apache.commons.io FileUtils)))


(def app-name "skylobby")
(def ^:dynamic app-version nil)

(def ^:dynamic ipc-port 12345)


(def minimap-size 512) ; display size in UI
(def start-pos-r 10.0) ; start position radius


(defn agent-string []
  (str app-name "-" app-version))

(defn user-agent [override]
  (if-not (string/blank? override)
    override
    (agent-string)))


(def main-class-name "spring_lobby.main")


; https://stackoverflow.com/a/16431226/984393
(defn manifest-version []
  (try
    (when-let [clazz (Class/forName main-class-name)]
      (log/trace "Discovered class" clazz)
      (when-let [loc (-> (.getProtectionDomain clazz) .getCodeSource .getLocation)]
        (log/trace "Discovered location" loc)
        (-> (str "jar:" loc "!/META-INF/MANIFEST.MF")
            URL. .openStream Manifest. .getMainAttributes
            (.getValue "Build-Number"))))
    (catch ClassNotFoundException _e
      (log/trace "Class not found, assuming running in dev and not as a jar"))
    (catch Exception e
      (log/trace e "Unable to read version from manifest"))))


(defn short-git-commit [git-commit-id]
  (when-not (string/blank? git-commit-id)
    (subs git-commit-id 0 (min 7 (count git-commit-id)))))


(defn hardware-client-id []
  (try
    (let [ni (NetworkInterface/getByInetAddress (InetAddress/getLocalHost))
          ha-bytes (.getHardwareAddress ni)
          crc (CRC32.)]
      (.update crc ha-bytes)
      (.getValue crc))
    (catch Exception e
      (log/error e "Error getting client id from hardware"))))

(def default-history-index -1)

(def player-name-color-types
  ["none"
   "team"
   "player"])


(defn curr-millis
  "Returns (System/currentTimeMillis)."
  []
  (System/currentTimeMillis))

; https://stackoverflow.com/a/50889042/984393
(defn str->bytes
  "Convert string to byte array."
  ([^String s]
   (str->bytes s "UTF-8"))
  ([^String s, ^String encoding]
   (.getBytes s encoding)))

(defn bytes->str
  "Convert byte array to String."
  [^bytes b]
  (String. b StandardCharsets/UTF_8))


(defn format-bytes
  "Returns a string of the given byte count in human readable format."
  [n]
  (when (number? n)
    (FileUtils/byteCountToDisplaySize (long n))))

(defn download-progress
  [{:keys [current done total]}]
  (if done
    (str (when total (format-bytes total)) " complete")
    (if (and current total)
      (str (format-bytes current)
           " / "
           (format-bytes total))
      "Downloading...")))


(defn decode [^String s]
  (URLDecoder/decode s (.name (StandardCharsets/UTF_8))))


(defn server-key [{:keys [server-url username]}]
  (str username "@" server-url))


(defn battle-channel-name [{:keys [battle-id channel-name]}]
  (or channel-name
      (str "__battle__" battle-id)))

(defn battle-channel-name? [channel-name]
  (and channel-name
       (string/starts-with? channel-name "__battle__")))

(defn non-battle-channels
  [channels]
  (->> channels
       (remove (comp string/blank? :channel-name))
       (remove (comp battle-channel-name? :channel-name))))

(defn user-channel-name? [channel-name]
  (and channel-name
       (string/starts-with? channel-name "@")))

(defn user-channel-name [username]
  (str "@" username))


(defn visible-channel [{:keys [by-server selected-tab-channel selected-tab-main]} server-key]
  (let [main-tab (get selected-tab-main server-key)]
    (if (= "battle" main-tab)
      (when-let [battle-id (get-in by-server [server-key :battle :battle-id])]
        (battle-channel-name battle-id))
      (get selected-tab-channel server-key))))

(defn to-number
  "Returns a nilable number from the given number or reads it from the given string."
  [string-or-number]
  (cond
    (number? string-or-number)
    string-or-number
    (boolean? string-or-number)
    (if string-or-number 1 0)
    (or (keyword? string-or-number) (string? string-or-number))
    (or
      (try
        (Long/parseLong (name string-or-number))
        (catch Exception e
          (log/trace e "Error parsing long from" string-or-number)))
      (try
        (Double/parseDouble (name string-or-number))
        (catch Exception e
          (log/trace e "Error parsing double from" string-or-number))))
    :else
    nil))

(defn random-client-id []
  ; https://stackoverflow.com/a/12768366
  (let [r (java.util.Random. (curr-millis))]
    (bit-and (.nextLong r) 0xffffffff)))

(defn client-id [state-atom {:keys [client-id-override client-id-type]}]
  (case client-id-type
    "zero" 0
    "hardware" (hardware-client-id)
    ; else random
    (if-let [r (to-number client-id-override)]
      r
      (let [r (random-client-id)]
        (swap! state-atom assoc :client-id-override r)
        r))))

(defn to-bool [v]
  (cond
    (boolean? v) v
    (number? v) (not (zero? v))
    (string? v)
    (case v
      "true" true
      "false" false
      (recur (to-number v)))
    :else
    (boolean v)))


; https://stackoverflow.com/a/39188819/984393
(defn base64-encode [bs]
  (.encodeToString (Base64/getEncoder) bs))

(defn base64-decode [^String to-decode]
  (String. (.decode (Base64/getDecoder) to-decode)))

; https://gist.github.com/jizhang/4325757
(defn md5-bytes [^String s]
  (let [algorithm (MessageDigest/getInstance "MD5")]
    (.digest algorithm (.getBytes s))))

(defn base64-md5 [password]
  (base64-encode (md5-bytes password)))


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

(defn format-duration [^Duration duration]
  (when duration
    (format "%d:%02d:%02d"
      (.toHours duration)
      (.toMinutesPart duration)
      (.toSecondsPart duration))))


(defn round [^double n]
  (when n
    (Math/round n)))

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

(defn sanitize-filter [s]
  (-> s (string/replace #"[^\p{Alnum}]" "") string/lower-case))

(defn matchmaking? [server-data]
  (->> server-data
       :compflags
       (filter #{"matchmaking"})
       seq
       boolean))


(defn hex-color-to-css [color]
  (string/replace color #"0x" "#"))


(defn nickname [{:keys [ai-name bot-name owner username]}]
  (if bot-name
    (str bot-name " (" ai-name ", " owner ")")
    (str username)))

(defn spring-script-color-to-int [rgbcolor]
  (let [[r g b] (string/split rgbcolor #"\s")
        color (colors/create-color
                :r (int (* 255 (Double/parseDouble b)))
                :g (int (* 255 (Double/parseDouble g)))
                :b (int (* 255 (Double/parseDouble r))))]
    color
    (colors/rgba-int color)))

(defn mod-name-git-no-ref [mod-name]
  (when mod-name
    (when-let [[_all mod-prefix _git] (re-find #"(.+)\sgit:([0-9a-f]+)$" mod-name)]
      (str mod-prefix " git:"))))

(defn mod-name-and-version
  ([mod-data]
   (mod-name-and-version mod-data nil))
  ([{:keys [git-commit-id modinfo]} {:keys [use-git-mod-version]}]
   (let [mod-name-only (:name modinfo)
         mod-version (or (when (and use-git-mod-version git-commit-id)
                           (str "git:" (short-git-commit git-commit-id)))
                         (:version modinfo))]
     {:mod-name (str mod-name-only " " mod-version)
      :mod-version mod-version
      :mod-name-only (:name modinfo)})))


(defn valid-servers [by-server]
  (->> (dissoc by-server :local)
       (remove (comp string/blank? first))
       (filter (comp :accepted second))))


(defn valid-server-keys [by-server]
  (->> (dissoc by-server :local)
       (remove (comp string/blank? first))
       (filter (comp :accepted second))
       (map first)))


; https://stackoverflow.com/a/17328219/984393
(defn deep-merge [& ms]
  (apply
    merge-with
    (fn [x y]
      (cond (map? y) (deep-merge x y)
            (vector? y) (concat x y)
            :else y))
    ms))

(defn update-chat-messages-fn
  ([username message]
   (update-chat-messages-fn username message false))
  ([username message ex]
   (fn [messages]
     (conj messages {:text message
                     :timestamp (curr-millis)
                     :username username
                     :message-type (when ex :ex)
                     :spads (when ex (spads/parse-spads-message message))
                     :vote (when-not ex (spads/parse-command-message message))
                     :relay (when-not ex (spads/parse-relay-message message))}))))


(defn random-color
  []
  (long (rand (* 255 255 255))))

(defn append-console-log [state-atom server-key source message]
  (swap! state-atom
    (fn [state]
      (if (contains? (:by-server state) server-key)
        (update-in state [:by-server server-key :console-log]
          (fn [console-log]
            (conj console-log {:timestamp (curr-millis)
                               :source source
                               :message message})))
        state))))


(defn remove-nonprintable [s]
  (when s
    (string/replace s #"\P{Print}" "")))


(defn bytes->hex
  "Convert a byte array to hex encoded string."
  [^bytes data]
  (Hex/encodeHexString data))

(defn game-type
  "Returns a keyword describing the general type of game based on the team and player counts, :duel,
  :team, :ffa, :teamffa."
  [allyteam-counts]
  (let [one-per-allyteam? (= #{1} (set allyteam-counts))
        num-allyteams (count allyteam-counts)]
    (cond
      (= 2 num-allyteams)
      (if one-per-allyteam?
        :duel
        :team)
      (< 2 num-allyteams)
      (if one-per-allyteam?
        :ffa
        :teamffa)
      :else
      :invalid)))


; https://clojuredocs.org/clojure.core/slurp
(defn slurp-bytes
  "Slurp the bytes from a slurpable thing"
  [x]
  (with-open [out (java.io.ByteArrayOutputStream.)]
    (io/copy (io/input-stream x) out)
    (.toByteArray out)))

(defn postprocess-byar-units-en [language-units-en]
  (->> language-units-en
       :en
       :units
       :factions
       (map-indexed
         (fn [i [_k v]]
           [(str i) {:name v}]))
       (into {})))


(defn log-to-file [log-path]
  (println "Setting up log to" log-path)
  (log/merge-config!
    {:min-level :info
     :appenders
     {:rotor (rotor/rotor-appender
               {:path log-path
                :max-size 5000000
                :backlog 5
                :output-fn (partial log/default-output-fn {:stacktrace-fonts {}})})}})
  (log/handle-uncaught-jvm-exceptions!))


; https://stackoverflow.com/a/4883851/984393
(defn is-port-open? [port]
  (try
    (with-open [_server (ServerSocket. port)]
      true)
    (catch Exception e
      (log/trace e "Port is not available" port)
      (log/info "Port is not available" port)
      false)))

(defn open-port []
  (try
    (with-open [_server (ServerSocket. 0)]
      true)
    (catch Exception e
      (log/error e "Error getting open port")
      false)))


(defn- parse-mod-name-git [mod-name]
  (or (re-find #"(.+)\s([0-9a-f]+)$" mod-name)
      (re-find #"(.+)\sgit:([0-9a-f]+)$" mod-name)
      (re-find #"(.+)\s(\$VERSION)$" mod-name)))

(defn mod-name-sans-git [mod-name]
  (when mod-name
    (if-let [[_all mod-prefix _git] (parse-mod-name-git mod-name)]
      mod-prefix
      mod-name)))

(defn mod-git-ref
  "Returns the git ref from the given mod name, or nil if it does not parse."
  [mod-name]
  (when mod-name
    (when-let [[_all _mod-prefix git] (parse-mod-name-git mod-name)]
      git)))

(defn mod-name-fix-git
  "Replace git commit with $VERSION for Spring"
  [mod-name]
  (let [mod-prefix (mod-name-sans-git mod-name)]
    (if (not= mod-prefix mod-name)
      (str mod-prefix " $VERSION")
      mod-name)))


; https://stackoverflow.com/a/320595/984393
(defn jar-file
  "Returns the file for the current running jar, or nil if it cannot be determined."
  []
  (try
    (when-let [clazz (Class/forName main-class-name)]
      (log/debug "Discovered class" clazz)
      (when-let [loc (-> (.getProtectionDomain clazz) .getCodeSource .getLocation .toURI)]
        (io/file loc)))
    (catch ClassNotFoundException _e
      (log/info "Class not found, assuming running in dev and not as a jar"))
    (catch Exception e
      (log/debug e "Unable to discover jar file"))))


; app update


; https://stackoverflow.com/a/48992863/984393
(defn vm-args []
  (let [vm-args (-> (ManagementFactory/getRuntimeMXBean) .getInputArguments)
        java-tool-options (System/getenv "JAVA_TOOL_OPTIONS")]
    (if java-tool-options
      (let [_java-tool-options-list (string/split java-tool-options #" ")]
        ; TODO remove java tool options from vm args
        vm-args)
      vm-args)))

(defn classpath []
  (-> (ManagementFactory/getRuntimeMXBean) .getClassPath))

(defn entry-point []
  (let [stack-trace (.getStackTrace (Throwable.))
        ^StackTraceElement stack-trace-element (last stack-trace)
        fully-qualified-class (.getClassName stack-trace-element)
        entry-method (.getMethodName stack-trace-element)]
    (when (not= "main" entry-method)
      (log/warn "Entry point is not a main" entry-method))
    fully-qualified-class))

(defn process-command []
  (-> (java.lang.ProcessHandle/current) .info .command (.orElse nil)))

(defn is-java? [command]
  (when (string? command)
    (or
      (string/ends-with? command "java")
      (string/ends-with? command "java.exe")
      (string/ends-with? command "javaw.exe"))))


; https://github.com/cemerick/url/blob/master/src/cemerick/url.cljx
(defn url-encode
  [string]
  (some-> string str (URLEncoder/encode "UTF-8") (.replace "+" "%20")))


