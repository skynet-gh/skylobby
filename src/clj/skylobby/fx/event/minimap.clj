(ns skylobby.fx.event.minimap
  (:require
    [skylobby.spring :as spring]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* false)


(def min-box-size 0.05)

(defn add-methods
  [multifn state-atom] ; TODO need to move event handler out of spring-lobby ns
  (defmethod multifn ::mouse-released
    [{:keys [minimap-scale minimap-width minimap-height map-details server-key]
      :or {minimap-scale 1.0}}]
    (future
      (try
        (let [[before _after] (swap-vals! state-atom dissoc :drag-team :drag-allyteam)]
          (when-let [{:keys [team x y]} (:drag-team before)]
            (let [{:keys [map-width map-height]} (-> map-details :smf :header)
                  x (int (* (/ x (* minimap-width minimap-scale)) map-width spring/map-multiplier))
                  z (int (* (/ y (* minimap-height minimap-scale)) map-height spring/map-multiplier))
                  team-data {:startposx x
                             :startposy z ; for SpringLobby bug
                             :startposz z}
                  state (swap! state-atom update-in
                               [:by-server server-key :battle :scripttags "game" (str "team" team)]
                               merge team-data)]
              (if-let [broadcast-fn (get-in state [:by-server server-key :server :broadcast-fn])]
                (let [scripttags (get-in state [:by-server server-key :battle :scripttags])]
                  (broadcast-fn [:skylobby.direct/battle-scripttags scripttags]))
                (log/warn "No broadcast-fn" server-key))))
          (when-let [{:keys [allyteam-id startx starty endx endy target]} (:drag-allyteam before)]
            (let [l (min startx endx)
                  t (min starty endy)
                  r (max startx endx)
                  b (max starty endy)
                  left (/ l (* minimap-scale minimap-width))
                  top (/ t (* minimap-scale minimap-height))
                  right (/ r (* minimap-scale minimap-width))
                  bottom (/ b (* minimap-scale minimap-height))]
              (if (and (< min-box-size (- right left))
                       (< min-box-size (- bottom top)))
                (let [state (swap! state-atom update-in [:by-server server-key :battle :scripttags "game" (str "allyteam" allyteam-id)]
                              (fn [allyteam]
                                (assoc allyteam
                                       :startrectleft left
                                       :startrecttop top
                                       :startrectright right
                                       :startrectbottom bottom)))]
                  (if-let [broadcast-fn (get-in state [:by-server server-key :server :broadcast-fn])]
                    (let [scripttags (get-in state [:by-server server-key :battle :scripttags])]
                      (broadcast-fn [:skylobby.direct/battle-scripttags scripttags]))
                    (log/warn "No broadcast-fn" server-key)))
                (if target
                  (do
                    (log/info "Clearing box" target)
                    (let [state (swap! state-atom update-in [:by-server server-key :battle :scripttags "game"] dissoc (str "allyteam" target))]
                      (if-let [broadcast-fn (get-in state [:by-server server-key :server :broadcast-fn])]
                        (let [scripttags (get-in state [:by-server server-key :battle :scripttags])]
                          (broadcast-fn [:skylobby.direct/battle-scripttags scripttags]))
                        (log/warn "No broadcast-fn" server-key))))
                  (log/info "Start box too small, ignoring" left top right bottom))))))
        (catch Exception e
          (log/error e "Error releasing minimap"))))))
