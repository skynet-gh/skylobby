(ns skylobby.fx.server
  (:require
    [cljfx.api :as fx]
    [clojure.string :as string]
    [skylobby.fs :as fs]
    skylobby.fx
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed]]
    [skylobby.fx.font-icon :as font-icon]
    [skylobby.util :as u]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(def server-window-width 640)
(def server-window-height 400)

(def fixed-order ; reversed
  [
   "Beyond All Reason (SSL)"
   "Beyond All Reason"
   "SpringLobby"
   "Spring Official"])
(def fixed-order-indices
  (->> fixed-order
       (map-indexed (fn [i s] [s (inc i)]))
       (into {})))


(defn- server-cell
  [[server-url server-data]]
  (let [server-alias (:alias server-data)]
    {:text (if server-alias
             (str server-alias " (" server-url ")")
             (str server-url))}))

(defn server-combo-box
  [{:fx/keys [context]
    :keys [on-value-changed]}]
  (let [
        server (fx/sub-val context :server)
        servers (fx/sub-val context :servers)
        value (->> servers
                   (filter (comp #{(first server)} first))
                   first)
        items (sort-by (juxt (comp (fnil - 0) fixed-order-indices :alias second) (comp :alias second) first) servers)]
    {:fx/type ext-recreate-on-key-changed
     :key items
     :desc
     {:fx/type :combo-box
      :value value
      :items items
      :prompt-text "< choose a server >"
      :button-cell server-cell
      :on-value-changed on-value-changed
      :cell-factory
      {:fx/cell-type :list-cell
       :describe server-cell}}}))


(defn- encoding-cell
  [encoding]
  {:text (str (name encoding))})


(defn normalize-server-data
  [server-data]
  (-> (merge
        {:auto-connect false
         :encoding nil
         :port u/default-server-port
         :ssl false}
        server-data)
      (update :port u/to-number)
      (update :spring-isolation-dir fs/canonical-path)))


(def servers-window-keys
  [:css :server-alias :server-auto-connect :server-edit :server-host :server-port
   :server-spring-root-draft :server-ssl :servers :show-servers-window])

(defn servers-window-impl
  [{:fx/keys [context]
    :keys [screen-bounds]}]
  (let [
        server-edit-data (fx/sub-val context :server-edit)
        server-host (:host server-edit-data)
        server-port (:port server-edit-data)
        server-alias (:alias server-edit-data)
        server-auto-connect (:auto-connect server-edit-data)
        server-encoding (:encoding server-edit-data)
        server-spring-root-draft (fx/sub-val context :server-spring-root-draft)
        server-ssl (:ssl server-edit-data)
        servers (fx/sub-val context :servers)
        show-servers-window (fx/sub-val context :show-servers-window)
        port (if (u/to-number server-port)
               server-port
               u/default-server-port)
        server-url (str server-host ":" port)
        server-edit [server-url server-edit-data]
        old-server-data (get servers server-url)]
    {:fx/type :stage
     :showing (boolean show-servers-window)
     :title (str u/app-name " Servers")
     :icons skylobby.fx/icons
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :show-servers-window}
     :x (skylobby.fx/fitx screen-bounds)
     :y (skylobby.fx/fity screen-bounds)
     :width (skylobby.fx/fitwidth screen-bounds server-window-width)
     :height (skylobby.fx/fitheight screen-bounds server-window-height)
     :scene
     {:fx/type :scene
      :stylesheets (fx/sub-ctx context skylobby.fx/stylesheet-urls-sub)
      :root
      (if show-servers-window
        {:fx/type :v-box
         :style {:-fx-font-size 16}
         :children
         [{:fx/type :h-box
           :alignment :center-left
           :children
           (concat
             [{:fx/type :label
               :alignment :center
               :text " Servers: "}
              {:fx/type server-combo-box
               :server server-edit
               :servers servers
               :on-value-changed {:event/type :spring-lobby/edit-server}}]
             (when server-edit
               [{:fx/type :button
                 :alignment :center
                 :on-action {:event/type :spring-lobby/dissoc-in
                             :path [:servers server-url]}
                 :text ""
                 :graphic
                 {:fx/type font-icon/lifecycle
                  :icon-literal "mdi-delete:16:white"}}]))}
          {:fx/type :pane
           :v-box/vgrow :always}
          {:fx/type :label
           :text "Add or update server:"}
          {:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :label
             :alignment :center
             :text " Host: "}
            {:fx/type :text-field
             :h-box/hgrow :always
             :text server-host
             :on-text-changed {:event/type :spring-lobby/assoc-in
                               :path [:server-edit :host]}}]}
          {:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :label
             :alignment :center
             :text " Port: "}
            {:fx/type :text-field
             :prompt-text (str u/default-server-port)
             :text-formatter
             {:fx/type :text-formatter
              :value-converter :integer
              :value (some-> server-port u/to-number int)
              :on-value-changed {:event/type :spring-lobby/assoc-in
                                 :path [:server-edit :port]}}}]}
          {:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :label
             :alignment :center
             :text " Alias: "}
            {:fx/type :text-field
             :h-box/hgrow :always
             :text server-alias
             :on-text-changed {:event/type :spring-lobby/assoc-in
                               :path [:server-edit :alias]}}]}
          {:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :label
             :alignment :center
             :text " Auto-connect: "}
            {:fx/type :check-box
             :h-box/hgrow :always
             :selected (boolean server-auto-connect)
             :on-selected-changed {:event/type :spring-lobby/assoc-in
                                   :path [:server-edit :auto-connect]}}]}
          {:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :label
             :alignment :center
             :text " SSL: "}
            {:fx/type :check-box
             :h-box/hgrow :always
             :selected (boolean server-ssl)
             :on-selected-changed {:event/type :spring-lobby/assoc-in
                                   :path [:server-edit :ssl]}}]}
          {:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :label
             :alignment :center
             :text " Encoding: "}
            {:fx/type :combo-box
             :value (or server-encoding
                        u/default-client-encoding)
             :items u/client-encodings
             :button-cell encoding-cell
             :on-value-changed {:event/type :spring-lobby/assoc-in
                                :path [:server-edit :encoding]}
             :cell-factory
             {:fx/cell-type :list-cell
              :describe encoding-cell}}]}
          {:fx/type :h-box
           :alignment :center-left
           :children
           (concat
             [{:fx/type :label
               :alignment :center
               :text " Spring root: "}
              {:fx/type :text-field
               :disable true
               :text (str server-spring-root-draft)
               :h-box/hgrow :always}]
             (when server-spring-root-draft
               [{:fx/type :button
                 :text ""
                 :on-action {:event/type :spring-lobby/dissoc-in
                             :path [:server-spring-root-draft]}
                 :graphic
                 {:fx/type font-icon/lifecycle
                  :icon-literal "mdi-close:16:white"}}])
             [{:fx/type :button
               :on-action {:event/type :spring-lobby/file-chooser-dir
                           :initial-dir server-spring-root-draft
                           :path [:server-spring-root-draft]}
               :text ""
               :graphic
               {:fx/type font-icon/lifecycle
                :icon-literal "mdi-file-find:16:white"}}])}
          (let [update-server-data {:port port
                                    :host server-host
                                    :alias server-alias
                                    :encoding server-encoding
                                    :spring-isolation-dir (fs/file server-spring-root-draft)
                                    :auto-connect (boolean server-auto-connect)
                                    :ssl (boolean server-ssl)}]
            {:fx/type :button
             :text (str
                     (if (contains? servers server-url)
                       "Update" "Add")
                     " server")
             :style {:-fx-font-size 20}
             :disable (or (string/blank? server-host)
                          (= (normalize-server-data old-server-data)
                             (normalize-server-data update-server-data)))
             :on-action {:event/type :spring-lobby/update-server
                         :server-url server-url
                         :server-data update-server-data}})]}
        {:fx/type :pane})}}))

(defn servers-window [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :servers-window
      (servers-window-impl state))))
