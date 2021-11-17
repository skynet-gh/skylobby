(ns skylobby.fx.server
  (:require
    [cljfx.api :as fx]
    [clojure.string :as string]
    [skylobby.fs :as fs]
    skylobby.fx
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed]]
    [skylobby.util :as u]
    [spring-lobby.fx.font-icon :as font-icon]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(def default-server-port 8200)

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


(def servers-window-keys
  [:css :server-alias :server-auto-connect :server-edit :server-host :server-port
   :server-spring-root-draft :server-ssl :servers :show-servers-window])

(defn servers-window-impl
  [{:fx/keys [context]
    :keys [screen-bounds]}]
  (let [server-alias (fx/sub-val context :server-alias)
        server-auto-connect (fx/sub-val context :server-auto-connect)
        server-edit (fx/sub-val context :server-edit)
        server-host (fx/sub-val context :server-host)
        server-port (fx/sub-val context :server-port)
        server-spring-root-draft (fx/sub-val context :server-spring-root-draft)
        server-ssl (fx/sub-val context :server-ssl)
        servers (fx/sub-val context :servers)
        show-servers-window (fx/sub-val context :show-servers-window)
        url (first server-edit)
        port (if (string/blank? (str server-port))
               default-server-port (str server-port))
        server-url (str server-host ":" port)]
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
              (assoc
                {:fx/type server-combo-box}
                :server server-edit
                :servers servers
                :on-value-changed {:event/type :spring-lobby/edit-server})]
             (when server-edit
               [{:fx/type :button
                 :alignment :center
                 :on-action {:event/type :spring-lobby/dissoc-in
                             :path [:servers url]}
                 :text ""
                 :graphic
                 {:fx/type font-icon/lifecycle
                  :icon-literal "mdi-delete:16:white"}}]))}
          {:fx/type :pane
           :v-box/vgrow :always}
          {:fx/type :label
           :text "New server:"}
          {:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :label
             :alignment :center
             :text " Host: "}
            {:fx/type :text-field
             :h-box/hgrow :always
             :text server-host
             :on-text-changed {:event/type :spring-lobby/assoc
                               :key :server-host}}]}
          {:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :label
             :alignment :center
             :text " Port: "}
            {:fx/type :text-field
             :text (str server-port)
             :prompt-text "8200"
             :on-text-changed {:event/type :spring-lobby/assoc
                               :key :server-port}}]}
          {:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :label
             :alignment :center
             :text " Alias: "}
            {:fx/type :text-field
             :h-box/hgrow :always
             :text server-alias
             :on-text-changed {:event/type :spring-lobby/assoc
                               :key :server-alias}}]}
          {:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :label
             :alignment :center
             :text " Auto-connect: "}
            {:fx/type :check-box
             :h-box/hgrow :always
             :selected (boolean server-auto-connect)
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :server-auto-connect}}]}
          {:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :label
             :alignment :center
             :text " SSL: "}
            {:fx/type :check-box
             :h-box/hgrow :always
             :selected (boolean server-ssl)
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :server-ssl}}]}
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
               :on-action {:event/type :spring-lobby/file-chooser-spring-root
                           :target [:server-spring-root-draft]}
               :text ""
               :graphic
               {:fx/type font-icon/lifecycle
                :icon-literal "mdi-file-find:16:white"}}])}
          (let [server-data {:port port
                             :host server-host
                             :alias server-alias
                             :spring-isolation-dir (fs/file server-spring-root-draft)
                             :auto-connect (boolean server-auto-connect)
                             :ssl (boolean server-ssl)}]
            {:fx/type :button
             :text (str
                     (if (contains? servers server-url) "Update" "Add")
                     " server")
             :style {:-fx-font-size 20}
             :disable (or (string/blank? server-host)
                          (= (update server-data :spring-isolation-dir fs/canonical-path)
                             (update (second server-edit) :spring-isolation-dir fs/canonical-path)))
             :on-action {:event/type :spring-lobby/update-server
                         :server-url server-url
                         :server-data server-data}})]}
        {:fx/type :pane})}}))

(defn servers-window [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :servers-window
      (servers-window-impl state))))
