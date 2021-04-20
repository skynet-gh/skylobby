(ns skylobby.fx.server
  (:require
    [clojure.string :as string]
    skylobby.fx
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed]]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.fs :as fs]
    [spring-lobby.util :as u]))


(def default-server-port 8200)


(defn- server-cell
  [[server-url server-data]]
  (let [server-alias (:alias server-data)]
    {:text (if server-alias
             (str server-alias " (" server-url ")")
             (str server-url))}))

(defn server-combo-box [{:keys [disable on-value-changed server servers]}]
  (let [value (->> servers
                   (filter (comp #{(first server)} first))
                   first)
        items (sort-by (juxt (comp :alias second) first) servers)]
    {:fx/type ext-recreate-on-key-changed
     :key items
     :desc
     {:fx/type :combo-box
      :disable (boolean disable)
      :value value
      :items items
      :prompt-text "< choose a server >"
      :button-cell server-cell
      :on-value-changed on-value-changed
      :cell-factory
      {:fx/cell-type :list-cell
       :describe server-cell}}}))


(def servers-window-keys
  [:server-alias :server-edit :server-host :server-port :server-spring-root-draft :server-ssl :servers :show-servers-window])

(defn servers-window
  [{:keys [server-alias server-edit server-host server-port server-spring-root-draft server-ssl servers show-servers-window]}]
  (let [url (first server-edit)
        port (if (string/blank? (str server-port))
               default-server-port (str server-port))
        server-url (str server-host ":" port)]
    {:fx/type :stage
     :showing (boolean show-servers-window)
     :title (str u/app-name " Servers")
     :icons skylobby.fx/icons
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :show-servers-window}
     :width 640
     :height 400
     :scene
     {:fx/type :scene
      :stylesheets skylobby.fx/stylesheets
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
             :text " SSL: "}
            {:fx/type :check-box
             :h-box/hgrow :always
             :selected (boolean server-ssl)
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :server-ssl}}]}
          {:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :label
             :alignment :center
             :text " Spring root: "}
            {:fx/type :text-field
             :text (str server-spring-root-draft)
             :h-box/hgrow :always
             :on-text-changed {:event/type :spring-lobby/assoc
                               :key :server-spring-root-draft}}]}
          {:fx/type :button
           :text (str
                   (if (contains? servers server-url) "Update" "Add")
                   " server")
           :style {:-fx-font-size 20}
           :disable (string/blank? server-host)
           :on-action {:event/type :spring-lobby/update-server
                       :server-url server-url
                       :server-data
                       {:port port
                        :host server-host
                        :alias server-alias
                        :spring-isolation-dir
                        (let [f (fs/file server-spring-root-draft)]
                          (when (fs/exists? f) f))
                        :ssl (boolean server-ssl)}}}]}
        {:fx/type :pane})}}))
