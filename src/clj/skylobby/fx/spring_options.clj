(ns skylobby.fx.spring-options
  (:require
    [cljfx.api :as fx]
    [cljfx.ext.node :as fx.ext.node]
    [clojure.string :as string]
    skylobby.fx
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed]]
    [skylobby.fx.sub :as sub]
    [skylobby.fx.tooltip-nofocus :as tooltip-nofocus]
    [skylobby.spring.script :as spring-script]
    [skylobby.util :as u]))


(set! *warn-on-reflection* true)


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
        show-hidden-modoptions (fx/sub-val context :show-hidden-modoptions)
        option-key (or option-key "modoptions")
        current-options (or current-options
                            (get-in scripttags ["game" option-key]))
        modoptions (if show-hidden-modoptions
                     modoptions
                     (->> modoptions
                          (remove (comp :hidden second))))
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
                   (remove (comp #{"section"} :type)) ; remove sections in middle
                   (map #(update % :key (comp keyword string/lower-case))))
        event-data (or event-data
                       {:event/type :skylobby.fx.event.battle/modoption-change})]
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
                      :server-key server-key
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
                      :server-key server-key
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
                       :server-key server-key
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
                      :server-key server-key
                      :singleplayer singleplayer)
                    :items (or (map (comp :key second) (:items i))
                               [])}}}}
                {:text (str (:def i))})))}}]}]}))

(defn modoptions-view
  [{:fx/keys [context]
    :keys [current-options event-data modoptions option-key server-key singleplayer]}]
  (let [sorted (sort-by (comp u/to-number first) modoptions)
        by-section (split-by (comp #{"section"} :type second) sorted)
        filter-modoptions (fx/sub-val context :filter-modoptions)
        filter-lc (or (when filter-modoptions (string/lower-case filter-modoptions))
                      "")
        show-only-changed-modoptions (fx/sub-val context :show-only-changed-modoptions)
        scripttags (fx/sub-val context get-in [:by-server server-key :battle :scripttags])
        changed-options (or current-options
                            (get-in scripttags ["game" (or option-key "modoptions")]))]
    {:fx/type ext-recreate-on-key-changed
     :key (str server-key)
     :desc
     {:fx/type :scroll-pane
      :fit-to-width true
      :hbar-policy :never
      :content
      {:fx/type :v-box
       :alignment :top-left
       :children
       (concat
         [{:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :label
             :text " Filter: "}
            {:fx/type :text-field
             :text (str filter-modoptions)
             :on-text-changed {:event/type :spring-lobby/assoc
                               :key :filter-modoptions}}]}
          {:fx/type :h-box
           :alignment :center-left
           :children
           [
            {:fx/type :check-box
             :selected (boolean show-only-changed-modoptions)
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :show-only-changed-modoptions}}
            {:fx/type :label
             :text " Show only changed "}]}]
         (->> by-section
              (map
                (fn [section]
                  (->> section
                       (filter
                         (fn [[_ modoption]]
                           (if-not (string/blank? filter-modoptions)
                             (some
                               (fn [kw]
                                 (when-let [s (get modoption kw)]
                                   (string/includes? (string/lower-case s) filter-lc)))
                               [:key :name :desc])
                             true)))
                       (into {}))))
              (map
                (fn [section]
                  (->> section
                       (filter
                         (fn [[_ modoption]]
                           (if show-only-changed-modoptions
                             (let [k (when (:key modoption)
                                       (string/lower-case (:key modoption)))
                                   t (:type modoption)]
                               (or (= "section" t)
                                   (and (contains? changed-options k)
                                        (not= (u/modoption-value t (get changed-options k))
                                              (u/modoption-value t (:def modoption))))))
                             true))))))
              (remove
                (fn [section]
                  (->> section
                       (remove (comp #{"section"} :type second))
                       empty?)))
              (mapv
                (fn [section]
                  {:fx/type modoptions-table
                   :current-options current-options
                   :event-data event-data
                   :modoptions section
                   :option-key option-key
                   :server-key server-key
                   :singleplayer singleplayer}))))}}}))
