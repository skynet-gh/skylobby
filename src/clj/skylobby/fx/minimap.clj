(ns skylobby.fx.minimap
  (:require
    clojure.set
    [clojure.string :as string]
    [spring-lobby.fs.smf :as smf]
    [spring-lobby.spring :as spring]
    [spring-lobby.util :as u]
    [taoensso.tufte :as tufte])
  (:import
    (javafx.embed.swing SwingFXUtils)
    (javafx.scene.paint Color)
    (javafx.scene.text Font FontWeight)))


(set! *warn-on-reflection* true)


(def minimap-sizes
  [256 384 512])
(def default-minimap-size
  (last minimap-sizes))


(defn minimap-start-boxes
  [scale minimap-width minimap-height scripttags drag-allyteam]
  (let [game (:game scripttags)
        allyteams (->> game
                       (filter (comp #(string/starts-with? % "allyteam") name first))
                       (map
                         (fn [[teamid team]]
                           (let [[_all id] (re-find #"allyteam(\d+)" (name teamid))]
                             [id team])))
                       (into {}))]
    (conj
      (->> allyteams
           (map
             (fn [[id {:keys [startrectbottom startrectleft startrectright startrecttop]}]]
               (when (and id
                          (number? startrectleft)
                          (number? startrecttop)
                          (number? startrectright)
                          (number? startrectbottom)
                          (number? minimap-width)
                          (number? minimap-height))
                 (merge
                   {:allyteam id
                    :x (int (* scale (* startrectleft minimap-width)))
                    :y (int (* scale (* startrecttop minimap-height)))
                    :width (int (* scale (* (- startrectright startrectleft) minimap-width)))
                    :height (int (* scale (* (- startrectbottom startrecttop) minimap-height)))}))))
           (filter some?))
      (when drag-allyteam
        (let [{:keys [startx starty endx endy]} drag-allyteam]
          {:allyteam (str (:allyteam-id drag-allyteam))
           :x (int (* scale (min startx endx)))
           :y (int (* scale (min starty endy)))
           :width (int (* scale (Math/abs (double (- endx startx)))))
           :height (int (* scale (Math/abs (double (- endy starty)))))})))))

(defn minimap-starting-points
  [battle-details map-details scripttags scale minimap-width minimap-height]
  (let [{:keys [map-width map-height]} (-> map-details :smf :header)
        teams (spring/teams battle-details)
        team-by-key (->> teams
                         (map second)
                         (map (juxt (comp spring/team-name :id :battle-status) identity))
                         (into {}))
        battle-team-keys (spring/team-keys teams)
        map-teams (spring/map-teams map-details)
        missing-teams (clojure.set/difference
                        (set (map spring/normalize-team battle-team-keys))
                        (set (map (comp spring/normalize-team first) map-teams)))
        midx (if map-width (quot (* spring/map-multiplier map-width) 2) 0)
        midz (if map-height (quot (* spring/map-multiplier map-height) 2) 0)
        choose-before-game (= "3" (some-> scripttags :game :startpostype str))
        all-teams (if choose-before-game
                    (concat map-teams (map (fn [team] [team {}]) missing-teams))
                    map-teams)]
    (when (and (number? map-width)
               (number? map-height)
               (number? minimap-width)
               (number? minimap-height))
      (->> all-teams
           (map
             (fn [[team-kw {:keys [startpos]}]]
               (let [{:keys [x z]} startpos
                     [_all team] (re-find #"(\d+)" (name team-kw))
                     normalized (spring/normalize-team team-kw)
                     scriptx (when choose-before-game
                               (some-> scripttags :game normalized :startposx u/to-number))
                     scriptz (when choose-before-game
                               (some-> scripttags :game normalized :startposz u/to-number))
                     scripty (when choose-before-game
                               (some-> scripttags :game normalized :startposy u/to-number))
                     ; ^ SpringLobby seems to use startposy
                     x (or scriptx x midx)
                     z (or scriptz scripty z midz)]
                 (when (and (number? x) (number? z))
                   {:x (int
                         (* scale
                           (- (* (/ x (* spring/map-multiplier map-width)) minimap-width)
                             (/ u/start-pos-r 2))))
                    :y (int
                         (* scale
                           (- (* (/ z (* spring/map-multiplier map-height)) minimap-height)
                              (/ u/start-pos-r 2))))
                    :team team
                    :color (or (-> team-by-key team-kw :team-color u/spring-color-to-javafx)
                               Color/WHITE)}))))
           (filter some?)
           doall))))

(defn minimap-pane-impl
  [{:keys [am-spec battle-details client-data drag-team drag-allyteam map-details map-name
           minimap-size minimap-type minimap-type-key scripttags singleplayer]}]
  (let [{:keys [smf]} map-details
        {:keys [minimap-height minimap-width]
         :or {minimap-height smf/minimap-display-size
              minimap-width smf/minimap-display-size}} smf
        max-width-or-height (max minimap-width minimap-height)
        minimap-size (or (u/to-number minimap-size)
                         default-minimap-size)
        minimap-scale (/ (* 1.0 minimap-size) max-width-or-height)
        starting-points (minimap-starting-points battle-details map-details scripttags minimap-scale minimap-width minimap-height)
        start-boxes (minimap-start-boxes minimap-scale minimap-width minimap-height scripttags drag-allyteam)
        minimap-image (case minimap-type
                        "metalmap" (:metalmap-image smf)
                        "heightmap" (:heightmap-image smf)
                        ; else
                        (:minimap-image-scaled smf))
        startpostype (->> scripttags
                          :game
                          :startpostype
                          spring/startpostype-name)]
    {:fx/type :stack-pane
     :style
     {:-fx-min-width minimap-size
      :-fx-max-width minimap-size
      :-fx-min-height minimap-size
      :-fx-max-height minimap-size}
     :on-scroll {:event/type :spring-lobby/minimap-scroll
                 :minimap-type-key minimap-type-key}
     :children
     (concat
       (if minimap-image
         (let [image (SwingFXUtils/toFXImage minimap-image nil)]
           [{:fx/type :image-view
             :image image
             :fit-width minimap-size
             :fit-height minimap-size
             :preserve-ratio true}])
         [{:fx/type :v-box
           :alignment :center
           :children
           [
            {:fx/type :label
             :text (str map-name)
             :style {:-fx-font-size 16}}
            {:fx/type :label
             :text (if map-details ; nil vs empty map
                     "(not found)"
                     "(loading...)")
             :alignment :center}]}])
       [(merge
          (when (or singleplayer (not am-spec))
            {:on-mouse-pressed {:event/type :spring-lobby/minimap-mouse-pressed
                                :startpostype startpostype
                                :starting-points starting-points
                                :start-boxes start-boxes}
             :on-mouse-dragged {:event/type :spring-lobby/minimap-mouse-dragged}
             :on-mouse-released {:event/type :spring-lobby/minimap-mouse-released
                                 :am-spec am-spec
                                 :channel-name (:channel-name battle-details)
                                 :client-data client-data
                                 :map-details map-details
                                 :minimap-width minimap-width
                                 :minimap-height minimap-height
                                 :singleplayer singleplayer}})
          {:fx/type :canvas
           :width (int (* minimap-scale minimap-width))
           :height (int (* minimap-scale minimap-height))
           :draw
           (fn [^javafx.scene.canvas.Canvas canvas]
             (let [gc (.getGraphicsContext2D canvas)
                   border-color (if (not= "minimap" minimap-type)
                                  Color/WHITE Color/BLACK)
                   random (= "Random" startpostype)
                   random-teams (when random
                                  (let [teams (spring/teams battle-details)]
                                    (set (map str (take (count teams) (iterate inc 0))))))
                   starting-points (if random
                                     (filter (comp random-teams :team) starting-points)
                                     starting-points)]
               (.clearRect gc 0 0 minimap-width minimap-height)
               (.setFill gc Color/RED)
               (.setFont gc (Font/font "Regular" FontWeight/BOLD 14.0))
               (if (#{"Fixed" "Random" "Choose before game"} startpostype)
                 (doseq [{:keys [x y team color]} starting-points]
                   (let [drag (when (and drag-team
                                         (= team (:team drag-team)))
                                drag-team)
                         x (or (:x drag) x)
                         y (or (:y drag) y)
                         xc (- x (if (= 1 (count team)) ; single digit
                                   (* u/start-pos-r -0.6)
                                   (* u/start-pos-r -0.2)))
                         yc (+ y (/ u/start-pos-r 0.75))
                         text (if random "?" team)
                         fill-color (if random Color/RED color)]
                     (.beginPath gc)
                     (.rect gc x y
                            (* 2 u/start-pos-r)
                            (* 2 u/start-pos-r))
                     (.setFill gc fill-color)
                     (.fill gc)
                     (.setStroke gc border-color)
                     (.stroke gc)
                     (.closePath gc)
                     (.setStroke gc Color/BLACK)
                     (.strokeText gc text xc yc)
                     (.setFill gc Color/WHITE)
                     (.fillText gc text xc yc)))
                 (when minimap-image
                   (doseq [{:keys [allyteam x y width height]} start-boxes]
                     (when (and x y width height)
                       (let [color (Color/color 0.5 0.5 0.5 0.5)
                             border 4
                             text allyteam
                             font-size 20.0
                             xt (+ x (quot font-size 2))
                             yt (+ y (* font-size 1.2))]
                         (.beginPath gc)
                         (.rect gc (+ x (quot border 2)) (+ y (quot border 2)) (inc (- width border)) (inc (- height border)))
                         (.setFill gc color)
                         (.fill gc)
                         (.setStroke gc Color/BLACK)
                         (.setLineWidth gc border)
                         (.stroke gc)
                         (.closePath gc)
                         (.setFont gc (Font/font "Regular" FontWeight/BOLD font-size))
                         (.setStroke gc Color/BLACK)
                         (.setLineWidth gc 2.0)
                         (.strokeText gc text xt yt)
                         (.setFill gc Color/WHITE)
                         (.fillText gc text xt yt))))))))})])}))


(defn minimap-pane [state]
  (tufte/profile {:dyanmic? true
                  :id :skylobby/ui}
    (tufte/p :minimap-pane
      (minimap-pane-impl state))))
