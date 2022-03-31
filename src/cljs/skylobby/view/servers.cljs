(ns skylobby.view.servers
  (:require
    [clojure.string :as string]
    [re-frame.core :as rf]
    [skylobby.view.servers-nav :as servers-nav]
    [skylobby.view.side-nav :as side-nav]
    [skylobby.util :as u]))


(defn listen [query-v]
  @(rf/subscribe query-v))

(defn servers-page [_]
  (let [{:keys [active-servers logins servers]} (listen [:skylobby/servers])
        server-url (listen [:skylobby/server-url])
        active-server-keys (set (map :server-key active-servers))
        username-drafts (listen [:skylobby/username-drafts])
        password-drafts (listen [:skylobby/password-drafts])
        {:keys [username password]} (get logins server-url)
        username (or (get username-drafts server-url)
                     username)
        password (or (get password-drafts server-url)
                     password)
        server-key (u/get-server-key server-url username)
        logged-in (contains? active-server-keys server-key)
        login-error (listen [:skylobby/login-error])]
    [:div {:class "flex"}
     [side-nav/side-nav]
     [:div {:class "flex-auto justify-center"}
      [servers-nav/servers-nav]
      [:div {:class "flex justify-center mb2"}
       [:label 
        {:class "f2 tc mr2"}
        " Server: "]
       [:select
        {:class "f3"
         :on-change #(rf/dispatch [:skylobby/assoc :server-url (-> % .-target .-value)])
         :value server-url}
        (for [[server-url server-config] servers]
          ^{:key server-url}
          [:option
           {:value server-url}
           (str server-url)])]]
      [:div {:class "flex justify-center mb2"}
       [:label 
        {:class "f3 tc mr2"}
        " Username: "]
       [:input
        {:class "f4"
         :value username
         :on-blur #(rf/dispatch [:skylobby/set-server-username server-url (-> % .-target .-value)])
         :on-change #(rf/dispatch [:skylobby/assoc-in [:username-drafts server-url] (-> % .-target .-value)])}]]
      [:div {:class "flex justify-center mb2"}
       [:label 
        {:class "f3 tc mr2"}
        " Password: "]
       [:input
        {:class "f4"
         :type "password"
         :value password
         :on-blur #(rf/dispatch [:skylobby/set-server-password server-url (-> % .-target .-value)])
         :on-change #(rf/dispatch [:skylobby/assoc-in [:password-drafts server-url] (-> % .-target .-value)])}]]
      [:div {:class "flex justify-center"}
       [:button
        {:class "f2 mt3 link dim ph3 pv1 mb2 dib white bg-near-black br2"
         :on-click #(rf/dispatch
                      (if logged-in
                        [:skylobby/disconnect-server server-key]
                        [:skylobby/connect-server {:server-url server-url :username username :password password}]))}
        (if logged-in
          "Disconnect"
          "Login")]]
      [:div {:class "flex-column"}
       (when-let [error (get login-error server-key)]
         (map-indexed
           (fn [i line]
             ^{:key i}
             [:div
              {:class "flex justify-center red"}
              line])
           (string/split-lines (str error))))]]]))
