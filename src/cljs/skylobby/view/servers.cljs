(ns skylobby.view.servers
  (:require
    [re-frame.core :as rf]
    [skylobby.view.servers-nav :as servers-nav]
    [skylobby.util :as u]))


(defn listen [query-v]
  @(rf/subscribe query-v))

(defn servers-page [_]
  (let [{:keys [active-servers logins servers]} (listen [:skylobby/servers])
        active-server-keys (set (map :server-key active-servers))
        username-drafts (listen [:skylobby/username-drafts])
        password-drafts (listen [:skylobby/password-drafts])]
    [:div
     [servers-nav/servers-nav]
     [:div {:class "flex justify-center"}
      [:table
       [:thead
        [:tr
         [:th "Alias"]
         [:th "URL"]
         [:th "Username"]
         [:th "Password"]
         [:th "Actions"]]]
       [:tbody
        (for [[server-url server-config] servers]
          (let [username (or (get username-drafts server-url)
                             (get-in logins [server-url :username]))
                password (or (get password-drafts server-url)
                             (get-in logins [server-url :password]))
                server-key (u/get-server-key server-url username)]
            ^{:key server-url}
            [:tr
             [:td (:alias server-config)]
             [:td server-url]
             [:td
              [:input
               {
                :value username
                :on-blur #(rf/dispatch [:skylobby/set-server-username server-url (-> % .-target .-value)])
                :on-change #(rf/dispatch [:skylobby/assoc-in [:username-drafts server-url] (-> % .-target .-value)])}]]
             [:td
              [:input
               {
                :type "password"
                :value password
                :on-blur #(rf/dispatch [:skylobby/set-server-password server-url (-> % .-target .-value)])
                :on-change #(rf/dispatch [:skylobby/assoc-in [:password-drafts server-url] (-> % .-target .-value)])}]]
             [:td
              (let [logged-in (contains? active-server-keys server-key)]
                [:button
                 {:class "f6 link dim ph3 pv1 mb2 dib white bg-near-black"
                  :on-click #(rf/dispatch
                               (if logged-in
                                 [:skylobby/disconnect-server server-key]
                                 [:skylobby/connect-server {:server-url server-url :username username :password password}]))}
                 (if logged-in
                   "Disconnect"
                   "Login")])]]))]]]]))
