(ns skylobby.fx.spring-options
  (:require
    [cljfx.api :as fx]
    [cljfx.ext.node :as fx.ext.node]
    [clojure.string :as string]
    skylobby.fx
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed]]
    [skylobby.fx.sub :as sub]
    [skylobby.fx.tooltip-nofocus :as tooltip-nofocus]
    [skylobby.util :as u]
    [spring-lobby.spring.script :as spring-script]))


; https://clojuredocs.org/clojure.core/split-with#example-5e48288ce4b0ca44402ef839
(defn split-by [pred coll]
  (lazy-seq
    (when-let [s (seq coll)]
      (let [!pred (complement pred)
            [xs ys] (split-with !pred s)]
        (if (seq xs)
          (cons xs (split-by pred ys))
          (let [skip (take-while pred s)
                others (drop-while pred s)
                [xs ys] (split-with !pred others)]
            (cons (concat skip xs)
                  (split-by pred ys))))))))

(defn modoptions-table
  [{:fx/keys [context]
    :keys [current-options event-data modoptions option-key singleplayer server-key]}]
  (let [am-host (fx/sub-ctx context sub/am-host server-key)
        am-spec (fx/sub-ctx context sub/am-spec server-key)
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        channel-name (fx/sub-ctx context skylobby.fx/battle-channel-sub server-key)
        scripttags (fx/sub-val context get-in [:by-server server-key :battle :scripttags])
        option-key (or option-key "modoptions")
        current-options (or current-options
                            (get-in scripttags ["game" option-key]))
        first-option (-> modoptions first second)
        is-section (-> first-option :type (= "section"))
        header (when is-section first-option)
        section-options (if is-section
                          (rest modoptions)
                          modoptions)
        items (->> section-options
                   (sort-by (comp u/to-number first))
                   (map second)
                   (filter :key)
                   (map #(update % :key (comp keyword string/lower-case))))
        event-data (or event-data
                       {:event/type :spring-lobby/modoption-change})]
    {:fx/type :v-box
     :children
     [{:fx/type :label
       :text (str (:name header))
       :style {:-fx-font-size 18}}
      {:fx/type :label
       :text (str (:desc header))
       :style {:-fx-font-size 14}}
      {:fx/type :table-view
       :column-resize-policy :constrained
       :items items
       :style {:-fx-pref-height (+ 60 (* 40 (count items)))}
       :columns
       [{:fx/type :table-column
         :text "Name"
         :cell-value-factory identity
         :cell-factory
         {:fx/cell-type :table-cell
          :describe
          (fn [i]
            {:text ""
             :graphic
             {:fx/type fx.ext.node/with-tooltip-props
              :props
              {:tooltip
               {:fx/type tooltip-nofocus/lifecycle
                :style {:-fx-font-size 16}
                :show-delay skylobby.fx/tooltip-show-delay
                :text (str (when-let [k (:key i)]
                             (name k))
                           "\n\n"
                           (:desc i))}}
              :desc
              (merge
                {:fx/type :label
                 :text (or (some-> i :name name str)
                           "")}
                (when-let [v (get current-options (some-> i :key name str))]
                  (when (not (spring-script/tag= i v))
                    {:style {:-fx-font-weight :bold}})))}})}}
        {:fx/type :table-column
         :text "Value"
         :cell-value-factory identity
         :cell-factory
         {:fx/cell-type :table-cell
          :describe
          (fn [i]
            (let [v (get current-options (some-> i :key name str))]
              (case (:type i)
                "bool"
                {:text ""
                 :graphic
                 {:fx/type ext-recreate-on-key-changed
                  :key (str (:key i))
                  :desc
                  {:fx/type fx.ext.node/with-tooltip-props
                   :props
                   {:tooltip
                    {:fx/type tooltip-nofocus/lifecycle
                     :style {:-fx-font-size 16}
                     :show-delay skylobby.fx/tooltip-show-delay
                     :text (str (name (:key i)) "\n\n" (:desc i))}}
                   :desc
                   {:fx/type :check-box
                    :selected (u/to-bool (or v (:def i)))
                    :on-selected-changed
                    (assoc event-data
                      :am-host am-host
                      :channel-name channel-name
                      :client-data client-data
                      :modoption-key (:key i)
                      :modoption-type (:type i)
                      :option-key option-key
                      :singleplayer singleplayer)
                    :disable (and (not singleplayer) am-spec)}}}}
                "string"
                {:text ""
                 :graphic
                 {:fx/type ext-recreate-on-key-changed
                  :key (str (:key i))
                  :desc
                  {:fx/type fx.ext.node/with-tooltip-props
                   :props
                   {:tooltip
                    {:fx/type tooltip-nofocus/lifecycle
                     :style {:-fx-font-size 16}
                     :show-delay skylobby.fx/tooltip-show-delay
                     :text (str (name (:key i)) "\n\n" (:desc i))}}
                   :desc
                   {:fx/type :text-field
                    :disable (and (not singleplayer) am-spec)
                    :text (str (or v (:def i)))
                    :on-text-changed
                    (assoc event-data
                      :am-host am-host
                      :channel-name channel-name
                      :client-data client-data
                      :modoption-key (:key i)
                      :modoption-type (:type i)
                      :option-key option-key
                      :singleplayer singleplayer)}}}}
                "number"
                {:text ""
                 :graphic
                 {:fx/type ext-recreate-on-key-changed
                  :key (str (:key i))
                  :desc
                  {:fx/type fx.ext.node/with-tooltip-props
                   :props
                   {:tooltip
                    {:fx/type tooltip-nofocus/lifecycle
                     :style {:-fx-font-size 16}
                     :show-delay skylobby.fx/tooltip-show-delay
                     :text (str (name (:key i)) "\n\n" (:desc i))}}
                   :desc
                   {:fx/type :text-field
                    :disable (and (not singleplayer) am-spec)
                    :text-formatter
                    {:fx/type :text-formatter
                     :value-converter :number
                     :value (u/to-number (or v (:def i)))
                     :on-value-changed
                     (assoc event-data
                       :am-host am-host
                       :channel-name channel-name
                       :client-data client-data
                       :modoption-key (:key i)
                       :modoption-type (:type i)
                       :option-key option-key
                       :singleplayer singleplayer)}}}}}
                "list"
                {:text ""
                 :graphic
                 {:fx/type ext-recreate-on-key-changed
                  :key (str (:key i))
                  :desc
                  {:fx/type fx.ext.node/with-tooltip-props
                   :props
                   {:tooltip
                    {:fx/type tooltip-nofocus/lifecycle
                     :style {:-fx-font-size 16}
                     :show-delay skylobby.fx/tooltip-show-delay
                     :text (str (name (:key i)) "\n\n" (:desc i))}}
                   :desc
                   {:fx/type :combo-box
                    :disable (and (not singleplayer) am-spec)
                    :value (or v (:def i))
                    :on-value-changed
                    (assoc event-data
                      :am-host am-host
                      :channel-name channel-name
                      :client-data client-data
                      :modoption-key (:key i)
                      :modoption-type (:type i)
                      :option-key option-key
                      :singleplayer singleplayer)
                    :items (or (map (comp :key second) (:items i))
                               [])}}}}
                {:text (str (:def i))})))}}]}]}))

(defn modoptions-view
  [{:keys [current-options event-data modoptions option-key server-key singleplayer]}]
  (let [sorted (sort-by (comp u/to-number first) modoptions)
        by-section (split-by (comp #{"section"} :type second) sorted)]
    {:fx/type :scroll-pane
     :fit-to-width true
     :hbar-policy :never
     :content
     {:fx/type :v-box
      :alignment :top-left
      :children
      (mapv
        (fn [section]
          {:fx/type modoptions-table
           :current-options current-options
           :event-data event-data
           :modoptions section
           :option-key option-key
           :server-key server-key
           :singleplayer singleplayer})
        by-section)}}))
