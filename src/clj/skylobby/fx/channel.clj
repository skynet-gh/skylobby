(ns skylobby.fx.channel
  (:require
   [cljfx.api :as fx]
   [clojure.string :as string]
   [skylobby.chat :as chat]
   [skylobby.fx :refer [monospace-font-family]]
   [skylobby.fx.ext :refer [ext-focused-by-default
                            ext-recreate-on-key-changed
                            ext-scroll-on-create
                            ext-with-auto-complete-word
                            ext-with-context-menu]]
   [skylobby.fx.font-icon :as font-icon]
   [skylobby.fx.rich-text :as fx.rich-text]
   [skylobby.fx.sub :as sub]
   [skylobby.fx.tooltip-nofocus :as tooltip-nofocus]
   [skylobby.fx.virtualized-scroll-pane :as fx.virtualized-scroll-pane]
   [skylobby.util :as u]
   [taoensso.timbre :as log]
   [taoensso.tufte :as tufte])
  (:import
   (javafx.scene.control TextField)
   (javafx.scene.input Clipboard ClipboardContent)
   (org.fxmisc.richtext.model ReadOnlyStyledDocumentBuilder SegmentOps StyledSegment)
   (org.nibor.autolink LinkExtractor LinkSpan LinkType)))


(set! *warn-on-reflection* true)


(def max-history 1000)


(def known-spads-commands
  ["!map"
   "!notify"
   "!pick"
   "!ring"
   "!set"
   "!status"
   "!vote"
   "!wakeup"])


(def default-font-size 18)
(def font-icon-size 20)

(defn font-size-or-default [font-size]
  (int (or (when (number? font-size) font-size)
           default-font-size)))

(def irc-colors ; same are on fx.clj , here just for checking
  #{"00"
    "01"
    "02"
    "03"
    "04"
    "05"
    "06"
    "07"
    "08"
    "09"
    "10"
    "11"
    "12"
    "13"
    "14"
    "15"})

(def
  ^LinkExtractor
  link-extractor
  (-> (LinkExtractor/builder)
      (.linkTypes #{LinkType/URL LinkType/WWW})
      (.build)))

(defn text-style [font-size]
  {:-fx-font-family monospace-font-family
   :-fx-font-size (font-size-or-default font-size)})


(defn segment
  [text style]
  (StyledSegment. text style))

(defn channel-document
  ([messages]
   (channel-document messages nil))
  ([all-messages {:keys [color-my-username highlight my-username]}]
   (let [messages (take-last max-history all-messages)
         highlight (->> highlight
                        (filter some?)
                        (map string/trim)
                        (remove string/blank?))
         builder (ReadOnlyStyledDocumentBuilder. (SegmentOps/styledTextOps) "")]
     (when-not (= (count all-messages)
                  (count messages))
       (.addParagraph builder
                      ^java.util.List
                      (vec [(segment (str "< " (- (count all-messages) (count messages)) " previous messages >") ["text" "skylobby-chat-message"])])
                      ^java.util.List
                      []))
     (doseq [message (filter :timestamp messages)]
       (let [{:keys [message-type text timestamp username]} message]
         (.addParagraph builder
                        ^java.util.List
                        (vec
                         (concat
                          [(segment
                            (str "[" (u/format-hours timestamp) "] ")
                            ["text" "skylobby-chat-time"])
                           (segment
                            (str
                             (case message-type
                               :ex (str "* " username " ")
                               :join (str username " has joined")
                               :leave (str username " has left")
                               :info (str "* " text)
                      ; else
                               (str username ": ")))
                            ["text" (if (= :info message-type)
                                      "skylobby-chat-info"
                                      (str "skylobby-chat-username"
                                           (if message-type
                                             (str "-" (name message-type))
                                             (when (and color-my-username (= username my-username))
                                               "-me"))))])]
                          (when (or (not message-type)
                                    (= :ex message-type))
                            (let [links (seq (.extractLinks link-extractor text))
                                  segments (if links
                                             (:segs
                                              (reduce
                                               (fn [{:keys [i segs]} ^LinkSpan link]
                                                 (let [begin (if link (.getBeginIndex link) (count text))
                                                       end (when link (.getEndIndex link))]
                                                   {:i end
                                                    :segs
                                                    (concat
                                                     segs
                                                     (when (and i begin (not= i begin))
                                                       [{:text-segment (subs text i begin)
                                                         :is-url false}])
                                                     (when link
                                                       [{:text-segment (subs text begin end)
                                                         :is-url true}]))}))
                                               {:i 0
                                                :segs []}
                                               (concat links [nil])))
                                             [{:text-segment text
                                               :is-url false}])]
                              (->> segments
                                   (mapv
                                    (fn [{:keys [is-url text-segment]}]
                                      (segment
                                       (str text-segment)
                                       ["text"
                                        (if is-url
                                          "skylobby-chat-message-url"
                                          (if (and (seq highlight)
                                                   (some (fn [substr]
                                                           (and text-segment substr
                                                                (string/includes? (string/lower-case text-segment)
                                                                                  (string/lower-case substr))))
                                                         highlight))
                                            "skylobby-chat-message-highlight"
                                            (str "skylobby-chat-message"
                                                 (when message-type
                                                   (str "-" (name message-type))))))])))))

                            (map
                             (fn [[_all _ _irc-color-code text-segment]]
                               (if (contains? irc-colors _irc-color-code)
                                 (segment
                                  (str text-segment)
                                  ["text", (str "skylobby-chat-message-irc-" _irc-color-code)])
                                 (segment (str text-segment)
                                          ["text", (str "skylobby-chat-message"
                                                        (when message-type
                                                          (str "-" (name message-type))))])))
                             (re-seq #"([\u0003](\d\d))?([^\u0003]*)" text)))))
                        ^java.util.List
                        [])))
     (when-not (seq messages)
       (.addParagraph builder
                      ^java.util.List
                      (vec [(segment "< no messages >" ["text" "skylobby-chat-message"])])
                      ^java.util.List
                      []))
     (.build builder))))

(defn- get-text-area
  ^org.fxmisc.richtext.StyleClassedTextArea
  [^javafx.event.Event event area-id-css]
  (let [^javafx.scene.control.MenuItem menu-item (.getTarget event)
        ^javafx.scene.control.ContextMenu context-menu (.getParentPopup menu-item)
        ^javafx.scene.Node node (.getOwnerNode context-menu)
        ^javafx.scene.Scene scene (.getScene node)
        ^javafx.scene.Parent root (.getRoot scene)]
    (first (.lookupAll root area-id-css))))

(defn visible-messages-sub [context server-key channel-name]
  (let [hide-barmanager-messages (fx/sub-val context :hide-barmanager-messages)
        hide-joinas-spec (fx/sub-val context :hide-joinas-spec)
        hide-spads-set (fx/sub-ctx context sub/hide-spads-set)
        hide-vote-messages (fx/sub-val context :hide-vote-messages)
        ignore-users-set (fx/sub-ctx context sub/ignore-users-set server-key)
        filter-fn (partial chat/visible-message?
                           {:hide-barmanager-messages hide-barmanager-messages
                            :hide-joinas-spec hide-joinas-spec
                            :hide-spads-set hide-spads-set
                            :hide-vote-messages hide-vote-messages
                            :ignore-users-set ignore-users-set})
        messages (fx/sub-val context get-in [:by-server server-key :channels channel-name :messages])]
    (->> messages
         (filter filter-fn)
         reverse
         doall)))


(defn channel-view-history-impl
  [{:fx/keys [context]
    :keys [channel-name server-key]}]
  (let [username (fx/sub-val context get-in [:by-server server-key :username])
        chat-auto-scroll (fx/sub-val context :chat-auto-scroll)
        chat-font-size (fx/sub-val context :chat-font-size)
        chat-color-username (fx/sub-val context :chat-color-username)
        chat-highlight-username (fx/sub-val context :chat-highlight-username)
        chat-highlight-words (fx/sub-val context :chat-highlight-words)
        hide-barmanager-messages (fx/sub-val context :hide-barmanager-messages)
        hide-joinas-spec (fx/sub-val context :hide-joinas-spec)
        hide-spads-set (fx/sub-ctx context sub/hide-spads-set)
        hide-vote-messages (fx/sub-val context :hide-vote-messages)
        ignore-users-set (fx/sub-ctx context sub/ignore-users-set server-key)
        area-id (str "channel-text-area" channel-name "-" server-key)
        area-id (string/replace area-id #"[^_a-zA-Z0-9-]" "")
        area-id-css (str "#" area-id)
        messages (fx/sub-ctx context visible-messages-sub server-key channel-name)]
    {:fx/type ext-recreate-on-key-changed
     :key {:ignore ignore-users-set
           :joinas-spec hide-joinas-spec
           :server-key server-key
           :spads hide-spads-set
           :vote hide-vote-messages
           :hide-barmanager hide-barmanager-messages}
     :desc
     {:fx/type ext-scroll-on-create
      :desc
      {:fx/type fx.virtualized-scroll-pane/lifecycle
       :event-filter {:event/type :spring-lobby/filter-channel-scroll}
       :content
       {:fx/type ext-with-context-menu
        :props {:context-menu {:fx/type :context-menu
                               :items
                               [{:fx/type :menu-item
                                 :text "Copy"
                                 :on-action
                                 (fn [event]
                                   (when-let [area (get-text-area event area-id-css)]
                                     (log/info "Copying chat" channel-name "to clipboard")
                                     (let [clipboard (Clipboard/getSystemClipboard)
                                           content (ClipboardContent.)]
                                       (.putString content (.getSelectedText area))
                                       (.setContent clipboard content))))}
                                {:fx/type :menu-item
                                 :text "Select All"
                                 :on-action
                                 (fn [event]
                                   (when-let [area (get-text-area event area-id-css)]
                                     (log/info "Selecting all chat" channel-name)
                                     (.selectAll area)))}]}}
        :desc
        {:fx/type fx.rich-text/lifecycle-fast
         :id area-id
         :editable false
         :style (text-style chat-font-size)
         :wrap-text true
         :document
         [messages
          (fn [lines]
            (channel-document
             lines
             {:color-my-username chat-color-username
              :highlight
              (concat
               (when chat-highlight-words
                 (string/split chat-highlight-words #"[\s,]+"))
               (when chat-highlight-username
                 [username]))
              :my-username username}))
          chat-auto-scroll]}}}}}))

(defn channel-view-history
  [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
                 (tufte/p :channel-view-history
                          (channel-view-history-impl state))))


(defn channel-view-text [{:fx/keys [context] :keys [channel-name disable server-key]}]
  (let [message-draft (fx/sub-val context get-in [:message-drafts server-key channel-name])
        history-index (fx/sub-val context get-in [:by-server server-key :channels channel-name :history-index])]
    {:fx/type ext-recreate-on-key-changed
     :key (str history-index)
     :desc
     {:fx/type ext-focused-by-default
      :desc
      {:fx/type fx/ext-on-instance-lifecycle
       :on-created (fn [^TextField text-field]
                     (.positionCaret text-field (count message-draft)))
       :desc
       {:fx/type :text-field
        :disable (boolean disable)
        :id "channel-text-field"
        :text (str message-draft)
        :on-text-changed {:event/type :spring-lobby/assoc-in
                          :path [:message-drafts server-key channel-name]}
        :on-action {:event/type :skylobby.fx.event.chat/send
                    :channel-name channel-name
                    :message message-draft
                    :server-key server-key}
        :on-key-pressed {:event/type :spring-lobby/on-channel-key-pressed
                         :channel-name channel-name
                         :server-key server-key}}}}}))

(defn channel-send-button
  [{:fx/keys [context]
    :keys [channel-name disable server-key]}]
  (let [message-draft (fx/sub-val context get-in [:message-drafts server-key channel-name])]
    {:fx/type :button
     :text "Send"
     :disable (boolean (or disable (string/blank? message-draft)))
     :on-action {:event/type :skylobby.fx.event.chat/send
                 :channel-name channel-name
                 :message message-draft
                 :server-key server-key}}))

(defn channel-view-input
  [{:fx/keys [context]
    :keys [channel-name disable server-key usernames]}]
  (let [chat-auto-complete (fx/sub-val context :chat-auto-complete)
        is-battle-channel (u/battle-channel-name? channel-name)
        mute-path [:mute server-key (if is-battle-channel :battle channel-name)]
        mute (fx/sub-val context get-in mute-path)
        mute-ring (fx/sub-val context get-in [:mute-ring server-key])]
    {:fx/type :h-box
     :style-class ["skylobby-chat-input"]
     :children
     (concat
      [{:fx/type channel-send-button
        :channel-name channel-name
        :disable disable
        :server-key server-key}
       {:fx/type ext-recreate-on-key-changed
        :key chat-auto-complete
        :h-box/hgrow :always
        :desc
        (if chat-auto-complete
          {:fx/type ext-with-auto-complete-word
           :props {:auto-complete (concat known-spads-commands (map u/sanitize-filter usernames))}
           :desc {:fx/type channel-view-text
                  :channel-name channel-name
                  :disable disable
                  :server-key server-key}}
          {:fx/type channel-view-text
           :channel-name channel-name
           :disable disable
           :server-key server-key})}]
      (when is-battle-channel
        [{:fx/type :button
          :text ""
          :tooltip
          {:fx/type tooltip-nofocus/lifecycle
           :show-delay skylobby.fx/tooltip-show-delay
           :text
           (str
            (if mute-ring "Enable" "Disable")
            " mute sound for this server")}
          :on-action {:event/type (if mute-ring :spring-lobby/dissoc-in :spring-lobby/assoc-in)
                      :path [:mute-ring server-key]}
          :graphic
          {:fx/type font-icon/lifecycle
           :icon-literal (if mute-ring
                           (str "mdi-volume-off:" font-icon-size ":red")
                           (str "mdi-volume-high:" font-icon-size ":white"))}}])
      [{:fx/type :button
        :text ""
        :tooltip
        {:fx/type tooltip-nofocus/lifecycle
         :show-delay skylobby.fx/tooltip-show-delay
         :text
         (str
          (if mute "Enable" "Disable")
          " tab highlighting on new messages")}
        :on-action {:event/type (if mute :spring-lobby/dissoc-in :spring-lobby/assoc-in)
                    :path mute-path}
        :graphic
        {:fx/type font-icon/lifecycle
         :icon-literal (if mute
                         (str "mdi-message-bulleted-off:" font-icon-size ":red")
                         (str "mdi-message:" font-icon-size ":white"))}}])}))

(defn channel-view-users
  [{:fx/keys [context]
    :keys [channel-name server-key]}]
  (let [users (fx/sub-val context get-in [:by-server server-key :channels channel-name :users])]
    {:fx/type :v-box
     :children
     [{:fx/type :label
       :text (str (count users) " users in " channel-name)}
      {:fx/type :table-view
       :v-box/vgrow :always
       :column-resize-policy :constrained
       :items (->> users
                   keys
                   (sort String/CASE_INSENSITIVE_ORDER)
                   vec)
       :row-factory
       {:fx/cell-type :table-row
        :describe (fn [i]
                    {:context-menu
                     {:fx/type :context-menu
                      :items
                      [{:fx/type :menu-item
                        :text "Message"
                        :on-action {:event/type :spring-lobby/join-direct-message
                                    :username i}}]}})}
       :columns
       [{:fx/type :table-column
         :text "Username"
         :cell-value-factory identity
         :cell-factory
         {:fx/cell-type :table-cell
          :describe
          (fn [i]
            {:text (-> i str)
             :style-class ["text" "skylobby-chat-user-list"]})}}]}]}))


(defn channel-view-impl
  [{:fx/keys [context] :keys [channel-name disable hide-users server-key usernames]}]
  (let [selected-server-tab (fx/sub-val context :selected-server-tab)
        parsed-selected-server-tab (fx/sub-ctx context sub/parsed-selected-server-tab)]
    {:fx/type :h-box
     :children
     (if (or (= server-key selected-server-tab)
             (= server-key parsed-selected-server-tab))
       (concat
        [{:fx/type :v-box
          :h-box/hgrow :always
          :style-class ["skylobby-chat"]
          :children
          [{:fx/type channel-view-history
            :v-box/vgrow :always
            :channel-name channel-name
            :server-key server-key}
           {:fx/type channel-view-input
            :channel-name channel-name
            :disable disable
            :server-key server-key
            :usernames usernames}]}]
        (when (and (not hide-users)
                   channel-name
                   (not (string/starts-with? channel-name "@")))
          [{:fx/type channel-view-users
            :channel-name channel-name
            :server-key server-key}]))
       [])}))


(defn channel-view
  [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
                 (tufte/p :channel-view
                          (channel-view-impl state))))
