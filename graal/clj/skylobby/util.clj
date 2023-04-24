(ns skylobby.util
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [com.evocomputing.colors :as colors]
    java-time
    [skylobby.spads :as spads]
    [taoensso.timbre :as log]
    [taoensso.timbre.appenders.community.rotor :as rotor])
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
    (org.apache.commons.io FileUtils))
  (:gen-class))


(set! *warn-on-reflection* true)


(def app-name "skylobby")
(def ^:dynamic app-version nil)

(def default-ipc-port 12345)
(def default-server-port 8200)


(def minimap-size 512) ; display size in UI
(def start-pos-r 10.0) ; start position radius

(def default-client-encoding :utf-8)
(def client-encodings
  [default-client-encoding
   :ISO-8859-1
   :ascii])


; https://github.com/clojure/clojure/blob/28efe345d5e995dc152a0286fb0be81443a0d9ac/src/clj/clojure/instant.clj#L274-L279
(defn- read-file-tag [cs]
  (io/file cs))
(defn- read-url-tag [spec]
  (URL. spec))

; https://github.com/clojure/clojure/blob/0754746f476c4ddf6a6b699d9547830b2fdad17c/src/clj/clojure/core.clj#L7755-L7761
(def custom-readers
  {'spring-lobby/java.io.File skylobby.util/read-file-tag
   'spring-lobby/java.net.URL skylobby.util/read-url-tag})

; https://stackoverflow.com/a/23592006
(defmethod print-method java.io.File [^java.io.File f ^java.io.Writer w]
  (.write w (str "#spring-lobby/java.io.File " (pr-str (.getCanonicalPath f)))))
(defmethod print-method URL [url ^java.io.Writer w]
  (.write w (str "#spring-lobby/java.net.URL " (pr-str (str url)))))


(defn server-url [{:keys [host port]}]
  (when (and host port)
    (str host ":" port)))

(def default-servers
  (let [springlobby {:host "lobby.springrts.com"
                     :port default-server-port
                     :alias "Spring Official"}
        bar-host "server4.beyondallreason.info"
        bar {:host bar-host
             :port default-server-port
             :alias "Beyond All Reason"}
        bar-ssl {:host bar-host
                 :port 8201
                 :alias "Beyond All Reason (SSL)"
                 :ssl true}
        servers [springlobby bar bar-ssl]]
    (->> servers
         (map (juxt server-url identity))
         (into {}))))


(defn is-bar-server-url? [server-url]
  (and server-url
       (or (string/starts-with? server-url "bar.teifion.co.uk")
           (string/starts-with? server-url "road-flag.bnr.la")
           (string/includes? server-url "beyondallreason.info"))))


(defn agent-string []
  (str app-name "-" app-version))

(defn user-agent [override]
  (if-not (string/blank? override)
    override
    (agent-string)))


(def this-class-name "skylobby.util")


; https://stackoverflow.com/a/16431226/984393
(defn manifest-version []
  (try
    (when-let [clazz (Class/forName this-class-name)]
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

(defn version []
  (or (try
        (slurp (io/resource (str app-name ".version")))
        (catch Exception e
          (log/trace e "Error reading version from resource")))
      (manifest-version)
      "UNKNOWN"))


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
      "...")))


(defn decode [^String s]
  (URLDecoder/decode s (.name (StandardCharsets/UTF_8))))


(defn server-type
  "Returns a keyword representing the type of server given its key. Used for dispatching actions
  based on these broad server groups."
  [server-key]
  (cond
    (string? server-key) :spring-lobby
    (= :local server-key) :singleplayer
    (map? server-key)
    (if (:host server-key)
      :direct-host
      :direct-client)
    :else nil))

(defn server-key [{:keys [server-url username]}]
  (if (and server-url username)
    (str username "@" server-url)
    :local)) ; TODO


(defn battle-id-channel-name [battle-id]
  (str "__battle__" battle-id))

(defn battle-channel-name [{:keys [battle-id channel-name] :as d}]
  (if (or battle-id channel-name)
    (or channel-name
        (battle-id-channel-name battle-id))
    (throw (ex-info "Cannot get battle channel name from" {:data d}))))

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
      (when-let [battle (get-in by-server [server-key :battle])]
        (battle-channel-name battle))
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
    (when-let [[_all n] (re-find #"~?#?([\d\.]+)#?" skill)]
      (try
        (Double/parseDouble n)
        (catch Exception e
          (log/warn e "Error parsing skill" skill))))
    :else nil))

(defn sanitize-filter [s]
  (-> s (string/replace #"[^\p{Alnum}]" "") string/lower-case))

(defn matchmaking? [_server-data]
  false
  #_
  (->> server-data
       :compflags
       (filter #{"matchmaking"})
       seq
       boolean))


(defn hex-color-to-css [color]
  (string/replace color #"0x" "#"))


(defn nickname [{:keys [bot-name username]}]
  (if bot-name
    (str bot-name)
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
  ([{:keys [modinfo]} _opts]
   (let [mod-name-only (:name modinfo)
         mod-version (:version modinfo)]
     {:mod-name (str mod-name-only " " mod-version)
      :mod-version mod-version
      :mod-name-only (:name modinfo)})))


; servers defined by string
(defn valid-servers [by-server]
  (->> by-server
       (filter (comp string? first))
       (remove (comp string/blank? first))
       (filter (comp :accepted second))))


(defn valid-server-keys [by-server]
  (->> by-server
       valid-servers
       (map first)
       (sort-by name)))


; servers defined by maps
(defn complex-servers [state]
  (->> state
       :by-server
       (filter (comp map? first))))

(defn complex-server-keys [state]
  (->> state
       complex-servers
       (map first)
       (sort-by str))) ; TODO maybe


; https://stackoverflow.com/a/17328219/984393
(defn deep-merge [& ms]
  (apply
    merge-with
    (fn [x y]
      (cond (map? y) (deep-merge x y)
            (vector? y) (concat x y)
            :else y))
    ms))

(defn chat-message-data
  ([username message]
   (chat-message-data username message false))
  ([username message ex]
   {:text message
    :timestamp (curr-millis)
    :username username
    :message-type (when ex :ex)
    :spads (when ex (spads/parse-spads-message message))
    :vote (when-not ex (spads/parse-command-message message))
    :relay (when-not ex (spads/parse-relay-message message))}))


(defn random-color
  []
  (long (rand (* 255 255 255))))

(defn append-console-log-fn [server-key source message]
  (fn [state]
    (if (contains? (:by-server state) server-key)
      (update-in state [:by-server server-key :console-log]
        (fn [console-log]
          (conj console-log {:timestamp (curr-millis)
                             :source source
                             :message message
                             :message-type (first (string/split message #"\s+"))})))
      state)))

(defn append-console-log [state-atom server-key source message]
  (swap! state-atom
    (append-console-log-fn server-key source message)))


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
     {:rotor
      (assoc
        (rotor/rotor-appender
          {:path log-path
           :max-size 5000000
           :backlog 5})
        :output-fn (partial log/default-output-fn {:stacktrace-fonts {}}))}})
  (log/handle-uncaught-jvm-exceptions!))

(defn log-only-to-file [log-path]
  (log/merge-config!
    {:min-level :info
     :appenders
     {:rotor
      (assoc
        (rotor/rotor-appender
          {:path log-path
           :max-size 5000000
           :backlog 5})
        :output-fn (partial log/default-output-fn {:stacktrace-fonts {}}))
      :println {:enabled? false}}})
  (log/handle-uncaught-jvm-exceptions!))


; https://stackoverflow.com/a/4883851/984393
(defn is-port-open? [port]
  (try
    (with-open [_server (ServerSocket. port)]
      true)
    (catch Exception e
      (log/debug e "Port is not available" (pr-str port))
      (log/info "Port is not available" (pr-str port))
      false)))

(defn open-port []
  (try
    (with-open [server (ServerSocket. 0)]
      (.getLocalPort server))
    (catch Exception e
      (log/error e "Error getting open port")
      nil)))


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
    (when-let [clazz (Class/forName this-class-name)]
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


(defn modoption-value
  "Returns the normalized modoption value to be used in the start script."
  [modoption-type raw-value]
  (if (or (= "list" modoption-type)
          (= "string" modoption-type))
    (str raw-value)
    (if (= "bool" modoption-type)
      (to-number (to-bool raw-value))
      (to-number raw-value))))


(defn server-needs-battle-status-sync-check [server-data]
  (and (get-in server-data [:battle :battle-id])
       (let [username (:username server-data)
             sync-status (get-in server-data [:battle :users username :battle-status :sync])]
         (not= sync-status 1))))


(defn check-cooldown [cooldowns k]
  (if-let [{:keys [tries updated]} (get cooldowns k)]
    (if (and (number? tries) (number? updated))
      (let [cd (< (curr-millis)
                  (+ updated (* 1000 (Math/pow 2 tries))))] ; exponential backoff
        (if cd
          (do
            (log/info k "is on cooldown")
            false)
          true))
      true)
    true))

(defn update-cooldown [state-atom k]
  (swap! state-atom update-in [:cooldowns k]
    (fn [state]
      (-> state
          (update :tries (fnil inc 0))
          (assoc :updated (curr-millis))))))


(defn sync-number [sync-bool]
  (if sync-bool 1 2))


(defn is-url? [s]
  (try
    (or (string/starts-with? s "www.")
        (java.net.URL. s))
    true
    (catch Exception _e
      false)))
