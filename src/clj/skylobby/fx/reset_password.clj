(ns skylobby.fx.reset-password
  (:require
    [cljfx.api :as fx]
    skylobby.fx
    [skylobby.fx.server :as fx.server]
    [skylobby.fx.tooltip-nofocus :as tooltip-nofocus]
    [skylobby.util :as u]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(def reset-password-window-width 500)
(def reset-password-window-height 400)


(defn reset-password-window-impl
  [{:fx/keys [context]
    :keys [screen-bounds]}]
  (let [email (fx/sub-val context :email)
        reset-password-code (fx/sub-val context :reset-password-code)
        request-reset-password-response (fx/sub-val context :request-reset-password-response)
        reset-password-response (fx/sub-val context :reset-password-response)
        server (fx/sub-val context :server)
        servers (fx/sub-val context :servers)
        show-reset-password-window (fx/sub-val context :show-reset-password-window)]
    {:fx/type :stage
     :showing (boolean show-reset-password-window)
     :title (str u/app-name " Reset Password")
     :icons skylobby.fx/icons
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :show-reset-password-window}
     :x (skylobby.fx/fitx screen-bounds)
     :y (skylobby.fx/fity screen-bounds)
     :width (skylobby.fx/fitwidth screen-bounds reset-password-window-width)
     :height (skylobby.fx/fitheight screen-bounds reset-password-window-height)
     :scene
     {:fx/type :scene
      :stylesheets (fx/sub-ctx context skylobby.fx/stylesheet-urls-sub)
      :root
      (if show-reset-password-window
        {:fx/type :v-box
         :style {:-fx-font-size 16}
         :children
         (concat
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
               :text " Email: "}
              {:fx/type :text-field
               :h-box/hgrow :always
               :text email
               :on-text-changed {:event/type :spring-lobby/assoc
                                 :key :email}}]}
            {:fx/type :pane
             :v-box/vgrow :always}
            {:fx/type :label
             :wrap-text true
             :text (str request-reset-password-response)}
            {:fx/type :button
             :text "Request Email Verification Code"
             :style {:-fx-font-size 20}
             :tooltip
             {:fx/type tooltip-nofocus/lifecycle
              :show-delay skylobby.fx/tooltip-show-delay
              :text "Request a verification code to be sent to this email"}
             :disable (not (and server email))
             :on-action {:event/type :spring-lobby/request-reset-password
                         :server server
                         :email email}}]
           (when request-reset-password-response
             [{:fx/type :h-box
               :alignment :center-left
               :children
               [{:fx/type :label
                 :text " Code: "}
                {:fx/type :text-field
                 :h-box/hgrow :always
                 :text reset-password-code
                 :on-text-changed {:event/type :spring-lobby/assoc
                                   :key :reset-password-code}}]}
              {:fx/type :label
               :wrap-text true
               :text (str reset-password-response)}
              {:fx/type :button
               :text "Reset Password"
               :style {:-fx-font-size 20}
               :tooltip
               {:fx/type tooltip-nofocus/lifecycle
                :show-delay skylobby.fx/tooltip-show-delay
                :text "Reset password which will be emailed to this address"}
               :disable (not (and server email))
               :on-action {:event/type :spring-lobby/reset-password
                           :server server
                           :email email
                           :verification-code reset-password-code}}]))}
        {:fx/type :pane})}}))

(defn reset-password-window [state]
  (tufte/profile {:dyanmic? true
                  :id :skylobby/ui}
    (tufte/p :reset-password-window
      (reset-password-window-impl state))))
