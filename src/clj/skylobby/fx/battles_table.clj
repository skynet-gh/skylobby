(ns skylobby.fx.battles-table
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [cljfx.api :as fx]
    [cljfx.ext.table-view :as fx.ext.table-view]
    clojure.core.memoize
    java-time
    [skylobby.fs :as fs]
    skylobby.fx
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed ext-table-column-auto-size]]
    [skylobby.fx.flag-icon :as flag-icon]
    [skylobby.fx.font-icon :as font-icon]
    [skylobby.fx.sub :as sub]
    [skylobby.fx.tooltip-nofocus :as tooltip-nofocus]
    [skylobby.util :as u]
    [taoensso.tufte :as tufte])
  (:import
    (javafx.scene.image Image)))


(set! *warn-on-reflection* true)


(def battles-map-size 128)


(defn battles-table-impl
  [{:fx/keys [context] :keys [server-key]}]
  (let [battle-password (fx/sub-val context :battle-password)
        filter-battles (fx/sub-val context :filter-battles)
        hide-empty-battles (fx/sub-val context :hide-empty-battles)
        hide-locked-battles (fx/sub-val context :hide-locked-battles)
        hide-passworded-battles (fx/sub-val context :hide-passworded-battles)
        battle (fx/sub-val context get-in [:by-server server-key :battle])
        battles (fx/sub-val context get-in [:by-server server-key :battles])
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        server-url (:server-url client-data)
        spring-root (fx/sub-ctx context skylobby.fx/spring-root-sub server-url)
        {:keys [engines-by-version maps-by-name mods-by-name]} (fx/sub-ctx context sub/spring-resources spring-root)
        selected-battle (fx/sub-val context get-in [:by-server server-key :selected-battle])
        users (fx/sub-val context get-in [:by-server server-key :users])
        filter-lc (when-not (string/blank? filter-battles)
                    (string/lower-case filter-battles))
        now (fx/sub-val context :now)
        filtered-battles (->> battles
                              vals
                              (filter :battle-title)
                              (filter
                                (fn [{:keys [battle-map battle-modname battle-title]}]
                                  (if filter-lc
                                    (or (and battle-map (string/includes? (string/lower-case battle-map) filter-lc))
                                        (and battle-modname (string/includes? (string/lower-case battle-modname) filter-lc))
                                        (string/includes? (string/lower-case battle-title) filter-lc))
                                    true)))
                              (remove
                                (fn [{:keys [battle-passworded]}]
                                  (if hide-passworded-battles
                                    (= "1" battle-passworded)
                                    false)))
                              (remove
                                (fn [{:keys [battle-locked]}]
                                  (if hide-locked-battles
                                    (= "1" battle-locked)
                                    false)))
                              (remove
                                (fn [{:keys [users]}]
                                  (if hide-empty-battles
                                    (boolean (<= (count users) 1)) ; TODO bot vs human hosts
                                    false)))
                              (sort-by (juxt (comp count :users) :battle-spectators))
                              reverse
                              doall)
        battles-by-id (into {} (map (juxt :battle-id identity) filtered-battles))]
    {:fx/type :v-box
     :style {:-fx-font-size 16
             :-fx-min-height 128}
     :children
     [{:fx/type :h-box
       :alignment :center-left
       :children
       (concat
         [
          {:fx/type :label
           :text (str "Battles (" (count battles) ")")}
          {:fx/type :pane
           :h-box/hgrow :always}
          {:fx/type :check-box
           :selected (boolean hide-locked-battles)
           :on-selected-changed {:event/type :spring-lobby/assoc
                                 :key :hide-locked-battles}}
          {:fx/type :label
           :text (str " Hide locked ")}
          {:fx/type :check-box
           :selected (boolean hide-passworded-battles)
           :on-selected-changed {:event/type :spring-lobby/assoc
                                 :key :hide-passworded-battles}}
          {:fx/type :label
           :text (str " Hide passworded ")}
          {:fx/type :check-box
           :selected (boolean hide-empty-battles)
           :on-selected-changed {:event/type :spring-lobby/assoc
                                 :key :hide-empty-battles}}
          {:fx/type :label
           :text (str " Hide empty ")}
          {:fx/type :pane
           :h-box/hgrow :always}
          {:fx/type :label
           :text (str " Filter: ")}
          {:fx/type :text-field
           :text (str filter-battles)
           :on-text-changed {:event/type :spring-lobby/assoc
                             :key :filter-battles}}]
         (when-not (string/blank? filter-battles)
           [{:fx/type :button
             :on-action {:event/type :spring-lobby/dissoc
                         :key :filter-battles}
             :graphic
             {:fx/type font-icon/lifecycle
              :icon-literal "mdi-close:16:white"}}]))}
      {:fx/type fx.ext.table-view/with-selection-props
       :v-box/vgrow :always
       :props {:selection-mode :single
               :on-selected-item-changed {:event/type :spring-lobby/select-battle
                                          :server-key server-key}
               :selected-item (get battles-by-id selected-battle)}
       :desc
       {:fx/type ext-table-column-auto-size
        :items filtered-battles
        :desc
        {:fx/type :table-view
         :style {:-fx-font-size 15}
         :column-resize-policy :constrained
         :row-factory
         {:fx/cell-type :table-row
          :describe (fn [battle-data]
                      (let [
                            {:keys [battle-engine battle-id battle-map battle-modname battle-title battle-version host-username]} battle-data
                            battle-users (:users battle-data)]
                        {:on-mouse-clicked
                         {:event/type :spring-lobby/on-mouse-clicked-battles-row
                          :battle battle
                          :battle-password battle-password
                          :client-data client-data
                          :selected-battle selected-battle
                          :battle-passworded (= "1" (-> battles (get selected-battle) :battle-passworded))}
                         :tooltip
                         {:fx/type tooltip-nofocus/lifecycle
                          :style {:-fx-font-size 16}
                          :show-delay skylobby.fx/tooltip-show-delay
                          :text ""
                          :graphic
                          {:fx/type :v-box
                           :children
                           (concat
                             [
                              {:fx/type :label
                               :text (str battle-title "\n")}
                              {:fx/type :label
                               :text (str "Map: " battle-map)
                               :graphic
                               {:fx/type font-icon/lifecycle
                                :icon-literal (if (contains? maps-by-name battle-map)
                                                "mdi-check:16:green"
                                                "mdi-close:16:red")}}
                              {:fx/type :label
                               :text (str "Game: " battle-modname)
                               :graphic
                               {:fx/type font-icon/lifecycle
                                :icon-literal (if (contains? mods-by-name battle-modname)
                                                "mdi-check:16:green"
                                                "mdi-close:16:red")}}
                              {:fx/type :label
                               :text (str "Engine: " battle-engine " " battle-version "\n")
                               :graphic
                               {:fx/type font-icon/lifecycle
                                :icon-literal (if (contains? engines-by-version battle-version)
                                                "mdi-check:16:green"
                                                "mdi-close:16:red")}}
                              {:fx/type :label
                               :text (str "Players:\n\n")}]
                             (->> battle-users
                                  keys
                                  (filter string?)
                                  (sort String/CASE_INSENSITIVE_ORDER)
                                  (map
                                    (fn [player]
                                      (let [country (:country (get users player))]
                                        {:fx/type :label
                                         :text player
                                         :graphic
                                         {:fx/type flag-icon/flag-icon
                                          :no-text true
                                          :country-code country}})))))}}
                         :context-menu
                         {:fx/type :context-menu
                          :items
                          (concat
                            [{:fx/type :menu-item
                              :text "Join Battle"
                              :on-action {:event/type :spring-lobby/join-battle
                                          :battle battle
                                          :battle-password battle-password
                                          :client-data client-data
                                          :selected-battle battle-id
                                          :battle-passworded (= "1" (:battle-passworded (get battles battle-id)))}}]
                            (when (-> users (get host-username) :client-status :bot)
                              (concat
                                [{:fx/type :menu-item
                                  :text "!status battle"
                                  :on-action {:event/type :spring-lobby/send-message
                                              :client-data client-data
                                              :channel-name (u/user-channel-name host-username)
                                              :message "!status battle"
                                              :server-key server-key}}]
                                (when (-> users (get host-username) :client-status :ingame)
                                  [{:fx/type :menu-item
                                    :text "!status game"
                                    :on-action {:event/type :spring-lobby/send-message
                                                :client-data client-data
                                                :channel-name (u/user-channel-name host-username)
                                                :message "!status game"
                                                :server-key server-key}}]))))}}))}
         :columns
         [
          {:fx/type :table-column
           :text "Game"
           :pref-width 240
           :cell-value-factory :battle-modname
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [battle-modname]
              {:text (str battle-modname)
               :graphic
               {:fx/type font-icon/lifecycle
                :icon-literal (if (contains? mods-by-name battle-modname)
                                "mdi-check:16:green"
                                "mdi-close:16:red")}})}}
          {:fx/type :table-column
           :text "Status"
           :resizable false
           :pref-width 112
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [status]
              (let [{:keys [game-start-time] :as user-data} (->> status :host-username (get users))
                    ingame (-> user-data :client-status :ingame)]
                {:text ""
                 :graphic
                 {:fx/type :h-box
                  :children
                  (concat
                    (if (or (= "1" (:battle-passworded status))
                            (= "1" (:battle-locked status)))
                      [{:fx/type font-icon/lifecycle
                        :icon-literal "mdi-lock:16:yellow"}]
                      [{:fx/type :pane
                        :style {:-fx-pref-width 16}}])
                    [(if ingame
                       {:fx/type font-icon/lifecycle
                        :icon-literal "mdi-sword:16:red"}
                       {:fx/type font-icon/lifecycle
                        :icon-literal "mdi-checkbox-blank-circle-outline:16:green"})]
                    (when (and ingame game-start-time)
                      (let [now (or now (u/curr-millis))]
                        [{:fx/type ext-recreate-on-key-changed
                          :key (str now)
                          :desc
                          {:fx/type :label
                           :text
                           (str " "
                                (let [diff (- now game-start-time)]
                                  (when (pos? diff)
                                    (u/format-duration (java-time/duration diff :millis)))))}}])))}}))}}
          {:fx/type :table-column
           :text "Map"
           :pref-width 200
           :cell-value-factory :battle-map
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [battle-map]
              {:text ""
               :graphic
               {:fx/type :label
                :text (str battle-map)
                :graphic
                {:fx/type font-icon/lifecycle
                 :icon-literal (if (contains? maps-by-name battle-map)
                                 "mdi-check:16:green"
                                 "mdi-close:16:red")}}})}}
          {:fx/type :table-column
           :text "Play (Spec)"
           :resizable false
           :pref-width 110
           :cell-value-factory (juxt
                                 (comp count :users)
                                 #(or (u/to-number (:battle-spectators %)) 0))
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [[total-user-count spec-count]]
              {:text (str (if (and (number? total-user-count) (number? spec-count))
                            (- total-user-count spec-count)
                            total-user-count)
                          " (" spec-count ")")})}}
          {:fx/type :table-column
           :text "Battle Name"
           :pref-width 200
           :cell-value-factory :battle-title
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [battle-title] {:text (str battle-title)})}}
          {:fx/type :table-column
           :text "Country"
           :resizable false
           :pref-width 64
           :cell-value-factory #(:country (get users (:host-username %)))
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [country]
              {:text ""
               :graphic
               {:fx/type flag-icon/flag-icon
                :country-code country}})}}
          {:fx/type :table-column
           :text "Host"
           :pref-width 100
           :cell-value-factory :host-username
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [host-username] {:text (str host-username)})}}]}}}]}))


(defn minimap-image [map-name _minimap-updated]
  (let [url (-> map-name fs/minimap-image-cache-file io/as-url str)]
    (Image. url battles-map-size battles-map-size true false true)))

(def minimap-image-memoized
  (clojure.core.memoize/lru minimap-image))


(defn battles-table-with-images-impl
  [{:fx/keys [context] :keys [server-key]}]
  (let [battle-password (fx/sub-val context :battle-password)
        filter-battles (fx/sub-val context :filter-battles)
        hide-empty-battles (fx/sub-val context :hide-empty-battles)
        hide-locked-battles (fx/sub-val context :hide-locked-battles)
        hide-passworded-battles (fx/sub-val context :hide-passworded-battles)
        battle (fx/sub-val context get-in [:by-server server-key :battle])
        battles (fx/sub-val context get-in [:by-server server-key :battles])
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        server-url (:server-url client-data)
        spring-root (fx/sub-ctx context skylobby.fx/spring-root-sub server-url)
        {:keys [engines-by-version maps-by-name mods-by-name]} (fx/sub-ctx context sub/spring-resources spring-root)
        selected-battle (fx/sub-val context get-in [:by-server server-key :selected-battle])
        users (fx/sub-val context get-in [:by-server server-key :users])
        all-users users
        filter-lc (when-not (string/blank? filter-battles)
                    (string/lower-case filter-battles))
        now (fx/sub-val context :now)
        cached-minimap-updated (fx/sub-val context :cached-minimap-updated)
        filtered-battles (->> battles
                              vals
                              (filter :battle-title)
                              (filter
                                (fn [{:keys [battle-map battle-modname battle-title]}]
                                  (if filter-lc
                                    (or (and battle-map (string/includes? (string/lower-case battle-map) filter-lc))
                                        (and battle-modname (string/includes? (string/lower-case battle-modname) filter-lc))
                                        (string/includes? (string/lower-case battle-title) filter-lc))
                                    true)))
                              (remove
                                (fn [{:keys [battle-passworded]}]
                                  (if hide-passworded-battles
                                    (= "1" battle-passworded)
                                    false)))
                              (remove
                                (fn [{:keys [battle-locked]}]
                                  (if hide-locked-battles
                                    (= "1" battle-locked)
                                    false)))
                              (remove
                                (fn [{:keys [users]}]
                                  (if hide-empty-battles
                                    (boolean (<= (count users) 1)) ; TODO bot vs human hosts
                                    false)))
                              (sort-by (juxt (comp count :users) :battle-spectators))
                              reverse
                              doall)
        battles-by-id (into {} (map (juxt :battle-id identity) filtered-battles))]
    {:fx/type :v-box
     :style {:-fx-font-size 16
             :-fx-min-height 128}
     :children
     [{:fx/type :h-box
       :alignment :center-left
       :children
       (concat
         [
          {:fx/type :label
           :text (str "Battles (" (count battles) ")")}
          {:fx/type :pane
           :h-box/hgrow :always}
          {:fx/type :check-box
           :selected (boolean hide-locked-battles)
           :on-selected-changed {:event/type :spring-lobby/assoc
                                 :key :hide-locked-battles}}
          {:fx/type :label
           :text (str " Hide locked ")}
          {:fx/type :check-box
           :selected (boolean hide-passworded-battles)
           :on-selected-changed {:event/type :spring-lobby/assoc
                                 :key :hide-passworded-battles}}
          {:fx/type :label
           :text (str " Hide passworded ")}
          {:fx/type :check-box
           :selected (boolean hide-empty-battles)
           :on-selected-changed {:event/type :spring-lobby/assoc
                                 :key :hide-empty-battles}}
          {:fx/type :label
           :text (str " Hide empty ")}
          {:fx/type :pane
           :h-box/hgrow :always}
          {:fx/type :label
           :text (str " Filter: ")}
          {:fx/type :text-field
           :text (str filter-battles)
           :on-text-changed {:event/type :spring-lobby/assoc
                             :key :filter-battles}}]
         (when-not (string/blank? filter-battles)
           [{:fx/type :button
             :on-action {:event/type :spring-lobby/dissoc
                         :key :filter-battles}
             :graphic
             {:fx/type font-icon/lifecycle
              :icon-literal "mdi-close:16:white"}}]))}
      {:fx/type fx.ext.table-view/with-selection-props
       :v-box/vgrow :always
       :props {:selection-mode :single
               :on-selected-item-changed {:event/type :spring-lobby/select-battle
                                          :server-key server-key}
               :selected-item (get battles-by-id selected-battle)}
       :desc
       {:fx/type :table-view
        :style {:-fx-font-size 15}
        :items filtered-battles
        :row-factory
        {:fx/cell-type :table-row
         :editable false
         :describe
         (fn [battle-data]
           (let [
                 {:keys [battle-engine battle-id battle-map battle-modname battle-title battle-version host-username]} battle-data
                 battle-users (:users battle-data)]
             {:on-mouse-clicked
              {:event/type :spring-lobby/on-mouse-clicked-battles-row
               :battle battle
               :battle-password battle-password
               :client-data client-data
               :selected-battle selected-battle
               :battle-passworded (= "1" (-> battles (get selected-battle) :battle-passworded))}
              :tooltip
              {:fx/type tooltip-nofocus/lifecycle
               :style {:-fx-font-size 16}
               :show-delay skylobby.fx/tooltip-show-delay
               :text ""
               :graphic
               {:fx/type :v-box
                :children
                (concat
                  [
                   {:fx/type :label
                    :text (str battle-title "\n")}
                   {:fx/type :label
                    :text (str "Map: " battle-map)
                    :graphic
                    {:fx/type font-icon/lifecycle
                     :icon-literal (if (contains? maps-by-name battle-map)
                                     "mdi-check:16:green"
                                     "mdi-close:16:red")}}
                   {:fx/type :label
                    :text (str "Game: " battle-modname)
                    :graphic
                    {:fx/type font-icon/lifecycle
                     :icon-literal (if (contains? mods-by-name battle-modname)
                                     "mdi-check:16:green"
                                     "mdi-close:16:red")}}
                   {:fx/type :label
                    :text (str "Engine: " battle-engine " " battle-version "\n")
                    :graphic
                    {:fx/type font-icon/lifecycle
                     :icon-literal (if (contains? engines-by-version battle-version)
                                     "mdi-check:16:green"
                                     "mdi-close:16:red")}}
                   {:fx/type :label
                    :text (str "Players:\n\n")}]
                  (->> battle-users
                       keys
                       (sort String/CASE_INSENSITIVE_ORDER)
                       (map
                         (fn [player]
                           (let [country (:country (get users player))]
                             {:fx/type :label
                              :text player
                              :graphic
                              {:fx/type flag-icon/flag-icon
                               :no-text true
                               :country-code country}})))))}}
              :context-menu
              {:fx/type :context-menu
               :items
               (concat
                 [{:fx/type :menu-item
                   :text "Join Battle"
                   :on-action {:event/type :spring-lobby/join-battle
                               :battle battle
                               :battle-password battle-password
                               :client-data client-data
                               :selected-battle battle-id
                               :battle-passworded (= "1" (:battle-passworded (get battles battle-id)))}}]
                 (when (-> users (get host-username) :client-status :bot)
                   (concat
                     [{:fx/type :menu-item
                       :text "!status battle"
                       :on-action {:event/type :spring-lobby/send-message
                                   :client-data client-data
                                   :channel-name (u/user-channel-name host-username)
                                   :message "!status battle"
                                   :server-key server-key}}]
                     (when (-> users (get host-username) :client-status :ingame)
                       [{:fx/type :menu-item
                         :text "!status game"
                         :on-action {:event/type :spring-lobby/send-message
                                     :client-data client-data
                                     :channel-name (u/user-channel-name host-username)
                                     :message "!status game"
                                     :server-key server-key}}]))))}}))}
        :columns
        [{:fx/type :table-column
          :text "Minimap"
          :pref-width (+ 8 battles-map-size)
          :resizable false
          :sortable false
          :cell-value-factory :battle-map
          :cell-factory
          {:fx/cell-type :table-cell
           :describe
           (fn [map-name]
             {:text ""
              :graphic
              {:fx/type :stack-pane
               :style
               {:-fx-min-width battles-map-size
                :-fx-pref-width battles-map-size
                :-fx-max-width battles-map-size
                :-fx-min-height battles-map-size
                :-fx-pref-height battles-map-size
                :-fx-max-height battles-map-size}
               :children
               [{:fx/type :pane
                 :style
                 {:-fx-min-width battles-map-size
                  :-fx-pref-width battles-map-size
                  :-fx-max-width battles-map-size
                  :-fx-min-height battles-map-size
                  :-fx-pref-height battles-map-size
                  :-fx-max-height battles-map-size}}
                {:fx/type :image-view
                 :image (minimap-image-memoized map-name (get cached-minimap-updated map-name))}]}})}}
         {:fx/type :table-column
          :text "Details"
          :sortable false
          :pref-width 480
          :cell-value-factory identity
          :cell-factory
          {:fx/cell-type :table-cell
           :describe
           (fn [{:keys [battle-spectators battle-title host-username users]}]
             (let [
                   total-user-count (count users)
                   spec-count (or (u/to-number battle-spectators) 0)
                   player-count (if (and (number? total-user-count) (number? spec-count))
                                  (- total-user-count spec-count)
                                  total-user-count)
                   country (:country (get all-users host-username))]
               {:text ""
                :graphic
                {:fx/type :v-box
                 :children
                 [{:fx/type :label
                   :text (str "Battle Name: " battle-title)}
                  {:fx/type :label
                   :text (str "Host: " host-username)}
                  {:fx/type :label
                   :text (str "Players: " player-count)}
                  {:fx/type :label
                   :text (str "Spectators: " spec-count)}
                  {:fx/type :h-box
                   :children
                   [
                    {:fx/type :label
                     :text "Country: "}
                    {:fx/type flag-icon/flag-icon
                     :country-code country}]}]}}))}}
         {:fx/type :table-column
          :text "Resources"
          :sortable false
          :pref-width 480
          :cell-value-factory identity
          :cell-factory
          {:fx/cell-type :table-cell
           :describe
           (fn [{:keys [battle-engine battle-version battle-map battle-modname]}]
             {:text ""
              :graphic
              {:fx/type :v-box
               :children
               [
                {:fx/type :label
                 :text (str "Engine: " battle-engine " " battle-version "\n")
                 :graphic
                 {:fx/type font-icon/lifecycle
                  :icon-literal (if (contains? engines-by-version battle-version)
                                  "mdi-check:16:green"
                                  "mdi-close:16:red")}}
                {:fx/type :label
                 :text (str "Game: " battle-modname)
                 :graphic
                 {:fx/type font-icon/lifecycle
                  :icon-literal (if (contains? mods-by-name battle-modname)
                                  "mdi-check:16:green"
                                  "mdi-close:16:red")}}
                {:fx/type :label
                 :text (str "Map: " battle-map)
                 :graphic
                 {:fx/type font-icon/lifecycle
                  :icon-literal (if (contains? maps-by-name battle-map)
                                  "mdi-check:16:green"
                                  "mdi-close:16:red")}}]}})}}
         {:fx/type :table-column
          :text "Status"
          :resizable false
          :pref-width 112
          :cell-value-factory identity
          :cell-factory
          {:fx/cell-type :table-cell
           :describe
           (fn [status]
             (let [{:keys [game-start-time] :as user-data} (->> status :host-username (get users))
                   ingame (-> user-data :client-status :ingame)]
               {:text ""
                :graphic
                {:fx/type :h-box
                 :children
                 (concat
                   (if (or (= "1" (:battle-passworded status))
                           (= "1" (:battle-locked status)))
                     [{:fx/type font-icon/lifecycle
                       :icon-literal "mdi-lock:16:yellow"}]
                     [{:fx/type :pane
                       :style {:-fx-pref-width 16}}])
                   [(if ingame
                      {:fx/type font-icon/lifecycle
                       :icon-literal "mdi-sword:16:red"}
                      {:fx/type font-icon/lifecycle
                       :icon-literal "mdi-checkbox-blank-circle-outline:16:green"})]
                   (when (and ingame game-start-time)
                     (let [now (or now (u/curr-millis))]
                       [{:fx/type ext-recreate-on-key-changed
                         :key (str now)
                         :desc
                         {:fx/type :label
                          :text
                          (str " "
                               (let [diff (- now game-start-time)]
                                 (when (pos? diff)
                                   (u/format-duration (java-time/duration diff :millis)))))}}])))}}))}}]}}]}))

(defn battles-table [{:fx/keys [context] :as state}]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :battles-table
      (if (fx/sub-val context :battles-table-images)
        (battles-table-with-images-impl state)
        (battles-table-impl state)))))
