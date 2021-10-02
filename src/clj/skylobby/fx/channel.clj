(ns skylobby.fx.channel
  (:require
    [clojure.string :as string]
    [skylobby.fx :refer [monospace-font-family]]
    [skylobby.fx.ext :refer [ext-scroll-on-create with-scroll-text-prop with-scroll-text-flow-prop]]
    [skylobby.fx.rich-text :as fx.rich-text]
    [skylobby.fx.virtualized-scroll-pane :as fx.virtualized-scroll-pane]
    [spring-lobby.util :as u]
    [taoensso.tufte :as tufte])
  (:import
    (org.fxmisc.richtext.model ReadOnlyStyledDocumentBuilder SegmentOps StyledSegment)))


(set! *warn-on-reflection* true)


(def default-font-size 18)

(defn font-size-or-default [font-size]
  (int (or (when (number? font-size) font-size)
           default-font-size)))

(def irc-colors
  {"00" "rgb(255,255,255)"
   "01" "rgb(255,255,255)" ; use white for black since dark theme "rgb(0,0,0)"
   "02" "rgb(0,0,127)"
   "03" "rgb(0,147,0)"
   "04" "rgb(255,0,0)"
   "05" "rgb(127,0,0)"
   "06" "rgb(156,0,156)"
   "07" "rgb(252,127,0)"
   "08" "rgb(255,255,0)"
   "09" "rgb(0,252,0)"
   "10" "rgb(0,147,147)"
   "11" "rgb(0,255,255)"
   "12" "rgb(0,0,252)"
   "13" "rgb(255,0,255)"
   "14" "rgb(127,127,127)"
   "15" "rgb(210,210,210)"})

(defn text-style [font-size]
  {:-fx-font-family monospace-font-family
   :-fx-font-size (font-size-or-default font-size)})


(defn old-channel-texts [messages]
  (let [last-message-index (dec (count messages))]
    (->> messages
         (map-indexed vector)
         (mapcat
           (fn [[i {:keys [message-type text timestamp username]}]]
             (concat
               [{:fx/type :text
                 :text (str "[" (u/format-hours timestamp) "] ")
                 :style-class ["text" "skylobby-chat-time"]}
                {:fx/type :text
                 :text
                 (str
                   (case message-type
                     :ex (str "* " username " " text)
                     :join (str username " has joined")
                     :leave (str username " has left")
                     ; else
                     (str username ": ")))
                 :style-class ["text" (str "skylobby-chat-username" (when message-type (str "-" (name message-type))))]}]
               (when-not message-type
                 (map
                   (fn [[_all _ _irc-color-code text-segment]]
                     {:fx/type :text
                      :text (str text-segment)
                      :style-class ["text" "skylobby-chat-message"]})
                   (re-seq #"([\u0003](\d\d))?([^\u0003]+)" text)))
               (when-not (= i last-message-index)
                 [{:fx/type :text
                   :text "\n"}])))))))

(defn old-channel-view-history-impl
  [{:keys [chat-auto-scroll channel-name chat-font-size messages select-mode server-key]}]
  (let [messages (reverse messages)]
    (if select-mode
      (let [text (->> messages
                      (map
                        (fn [{:keys [ex text timestamp username]}]
                          (str
                            "[" (u/format-hours timestamp) "] "
                            (if ex
                              (str "* " username " " text)
                              (str username ": " text)))))
                      (string/join "\n"))]
        {:fx/type with-scroll-text-prop
         :props {:scroll-text [text chat-auto-scroll]}
         :desc
         {:fx/type :text-area
          :editable false
          :wrap-text true
          :style (text-style chat-font-size)
          :context-menu
          {:fx/type :context-menu
           :items
           [{:fx/type :menu-item
             :text "Color mode"
             :on-action {:event/type :spring-lobby/assoc-in
                         :path [:by-server server-key :channels channel-name :select-mode]
                         :value false}}]}}})
      (let [texts (old-channel-texts messages)]
        {:fx/type with-scroll-text-flow-prop
         :props {:auto-scroll [texts chat-auto-scroll]}
         :desc
         {:fx/type :scroll-pane
          :style {:-fx-min-width 200
                  :-fx-pref-width 200}
          :fit-to-width true
          :on-scroll {:event/type :spring-lobby/enable-auto-scroll-if-at-bottom}
          :context-menu
          {:fx/type :context-menu
           :items
           [{:fx/type :menu-item
             :text "Select mode"
             :on-action {:event/type :spring-lobby/assoc-in
                         :path [:by-server server-key :channels channel-name :select-mode]
                         :value true}}]}
          :content
          {:fx/type :text-flow
           :on-scroll {:event/type :spring-lobby/disable-auto-scroll}
           :style (text-style chat-font-size)
           :children texts}}}))))

(defn segment
  [text style]
  (StyledSegment. text style))

(defn channel-document
  ([messages]
   (channel-document messages nil))
  ([messages {:keys [highlight]}]
   (let [highlight (->> highlight
                        (filter some?)
                        (map string/trim)
                        (remove string/blank?))
         builder (ReadOnlyStyledDocumentBuilder. (SegmentOps/styledTextOps) "")]
     (doseq [message messages]
       (let [{:keys [message-type text timestamp username]} message]
         (.addParagraph builder
           ^java.util.List
           (vec
             (concat
               [
                (segment
                  (str "[" (u/format-hours timestamp) "] ")
                  ["text" "skylobby-chat-time"])
                (segment
                  (str
                    (case message-type
                      :ex (str "* " username " " text)
                      :join (str username " has joined")
                      :leave (str username " has left")
                      ; else
                      (str username ": ")))
                  ["text" (str "skylobby-chat-username" (when message-type (str "-" (name message-type))))])]
               (when-not message-type
                 (map
                   (fn [[_all _ _irc-color-code text-segment]]
                     (segment
                       (str text-segment)
                       ["text"
                        (if (and (seq highlight)
                                 (some (fn [substr]
                                         (and text-segment substr
                                              (string/includes? (string/lower-case text-segment)
                                                                (string/lower-case substr))))
                                       highlight))
                          "skylobby-chat-message-highlight"
                          "skylobby-chat-message")]))
                   (re-seq #"([\u0003](\d\d))?([^\u0003]+)" text)))))
           ^java.util.List
           [])))
     (when (seq messages)
       (.build builder)))))

(defn- channel-view-history-impl
  [{:keys [chat-auto-scroll chat-font-size chat-highlight-username chat-highlight-words ignore-users messages server-key username]}]
  (let [ignore-users-set (->> (get ignore-users server-key)
                              (filter second)
                              (map first)
                              set)]
    {:fx/type ext-scroll-on-create
     :desc
     {:fx/type fx.virtualized-scroll-pane/lifecycle
      :event-filter {:event/type :spring-lobby/filter-channel-scroll}
      :content
      {:fx/type fx.rich-text/lifecycle-fast
       :editable false
       :style (text-style chat-font-size)
       :wrap-text true
       :document
       [
        (->> messages
             (remove (comp ignore-users-set :username))
             (remove
               (fn [{:keys [message-type text]}]
                 (and (= :ex message-type)
                      text
                      (string/starts-with? text "* BarManager|"))))
             reverse)
        (fn [lines]
          (channel-document
            lines
            {:highlight
             (concat
               (when chat-highlight-words
                 (string/split chat-highlight-words #"[\s,]+"))
               (when chat-highlight-username
                 [username]))}))
        chat-auto-scroll]}}}))

(defn channel-view-history
  [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :channel-view-history
      (channel-view-history-impl state))))

(defn- channel-view-input [{:keys [channel-name client-data message-draft server-key]}]
  {:fx/type :h-box
   :children
   [{:fx/type :button
     :text "Send"
     :on-action {:event/type :spring-lobby/send-message
                 :channel-name channel-name
                 :client-data client-data
                 :message message-draft
                 :server-key server-key}}
    {:fx/type :text-field
     :id "channel-text-field"
     :h-box/hgrow :always
     :text (str message-draft)
     :on-text-changed {:event/type :spring-lobby/assoc-in
                       :path [:by-server server-key :message-drafts channel-name]}
     :on-action {:event/type :spring-lobby/send-message
                 :channel-name channel-name
                 :client-data client-data
                 :message message-draft
                 :server-key server-key}
     :on-key-pressed {:event/type :spring-lobby/on-channel-key-pressed
                      :channel-name channel-name
                      :server-key server-key}}]})

(defn- channel-view-users [{:keys [users]}]
  {:fx/type :table-view
   :column-resize-policy :constrained ; TODO auto resize
   :items (->> users
               keys
               (sort String/CASE_INSENSITIVE_ORDER)
               vec)
   :row-factory
   {:fx/cell-type :table-row
    :describe (fn [i]
                {
                 :context-menu
                 {:fx/type :context-menu
                  :items
                  [
                   {:fx/type :menu-item
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
         :style-class ["text" "skylobby-chat-user-list"]})}}]})

(def channel-state-keys
  [:chat-auto-scroll :chat-font-size :chat-highlight-username :chat-highlight-words :ignore-users])

(defn channel-view-impl
  [{:keys [channel-name channels hide-users]
    :as state}]
  (let [{:keys [users] :as channel-data} (get channels channel-name)]
    {:fx/type :h-box
     :children
     (concat
       [{:fx/type :v-box
         :h-box/hgrow :always
         :style {:-fx-font-size 16}
         :children
         [(merge
            {:fx/type channel-view-history
             :v-box/vgrow :always}
            state
            channel-data)
          (merge
            {:fx/type channel-view-input}
            state)]}]
       (when (and (not hide-users)
                  channel-name
                  (not (string/starts-with? channel-name "@")))
         [{:fx/type channel-view-users
           :users users}]))}))


(defn channel-view
  [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :channel-view
      (channel-view-impl state))))
