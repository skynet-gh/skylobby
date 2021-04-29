(ns skylobby.fx.register
  (:require
    [clojure.string :as string]
    skylobby.fx
    [skylobby.fx.server :as fx.server]
    [spring-lobby.util :as u]
    [taoensso.tufte :as tufte]))


(def register-window-keys
  [:css :email :password :password-confirm :register-response :server :servers :show-register-window
   :username])

(defn register-window
  [{:keys [css email password password-confirm register-response server servers show-register-window username]}]
  (tufte/profile {:id :skylobby/ui}
    (tufte/p :register-window
      {:fx/type :stage
       :showing (boolean show-register-window)
       :title (str u/app-name " Register")
       :icons skylobby.fx/icons
       :on-close-request {:event/type :spring-lobby/dissoc
                          :key :show-register-window}
       :width 500
       :height 400
       :scene
       {:fx/type :scene
        :stylesheets (skylobby.fx/stylesheet-urls css)
        :root
        (if show-register-window
          {:fx/type :v-box
           :style {:-fx-font-size 16}
           :children
           [
            (assoc
              {:fx/type fx.server/server-combo-box}
              :server server
              :servers servers
              :on-value-changed {:event/type :spring-lobby/on-change-server})
            {:fx/type :h-box
             :alignment :center-left
             :children
             [{:fx/type :label
               :text " Username: "}
              {:fx/type :text-field
               :text username
               :on-text-changed {:event/type :spring-lobby/username-change
                                 :server-url (first server)}}]}
            {:fx/type :h-box
             :alignment :center-left
             :children
             [{:fx/type :label
               :text " Password: "}
              {:fx/type :password-field
               :text password
               :on-text-changed {:event/type :spring-lobby/password-change
                                 :server-url (first server)}}]}
            {:fx/type :h-box
             :alignment :center-left
             :children
             [{:fx/type :label
               :text " Confirm: "}
              {:fx/type :password-field
               :text password-confirm
               :on-text-changed {:event/type :spring-lobby/assoc
                                 :key :password-confirm}}]}
            {:fx/type :h-box
             :alignment :center-left
             :children
             [{:fx/type :label
               :text " Email: "}
              {:fx/type :text-field
               :text email
               :on-text-changed {:event/type :spring-lobby/assoc
                                 :key :email}}]}
            {:fx/type :pane
             :v-box/vgrow :always}
            {:fx/type :label
             :text (str register-response)}
            {:fx/type :label
             :style {:-fx-text-fill "red"}
             :text (str (when (and (not (string/blank? password))
                                   (not (string/blank? password-confirm))
                                   (not= password password-confirm))
                          "Passwords do not match"))}
            {:fx/type :button
             :text "Register"
             :style {:-fx-font-size 20}
             :tooltip
             {:fx/type :tooltip
              :show-delay [10 :ms]
              :text "Register with server"}
             :disable (not (and server
                                username
                                password
                                password-confirm
                                (= password password-confirm)))
             :on-action {:event/type :spring-lobby/register
                         :server server
                         :username username
                         :password password
                         :email email}}]}
          {:fx/type :pane})}})))
