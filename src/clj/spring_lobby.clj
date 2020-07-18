(ns spring-lobby
  (:require
    [cljfx.api :as fx]))


(defonce *state
  (atom {}))

(defmulti event-handler :event/type)


(defn root-view [{{:keys []} :state}]
  {:fx/type :stage
   :showing true
   :title "Alt Spring Lobby"
   :width 300
   :height 100
   :scene {:fx/type :scene
           :root {:fx/type :v-box
                  :alignment :top-left
                  :children [{:fx/type :menu-bar
                              :menus
                              [{:fx/type :menu
                                :text "menu1"
                                :items [{:fx/type :menu-item
                                         :text "menu1 item1"}
                                        {:fx/type :menu-item
                                         :text "menu1 item2"}]}
                               {:fx/type :menu
                                :text "menu2"
                                :items [{:fx/type :menu-item
                                         :text "menu2 item1"}
                                        {:fx/type :menu-item
                                         :text "menu2 item2"}]}
                               {:fx/type :menu
                                :text "menu3"
                                :items [{:fx/type :menu-item
                                         :text "menu3 item1"}
                                        {:fx/type :menu-item
                                         :text "menu3 item2"}]}
                               {:fx/type :menu
                                :text "menu4"
                                :items [{:fx/type :menu-item
                                         :text "menu4 item1"}
                                        {:fx/type :menu-item
                                         :text "menu4 item2"}]}]}
                             {:fx/type :label
                              :text "Hello world"}]}}})

(def renderer
  (fx/create-renderer
    :middleware (fx/wrap-map-desc (fn [state]
                                    {:fx/type root-view
                                     :state state}))
    :opts {:fx.opt/map-event-handler event-handler}))


(fx/mount-renderer *state renderer)
