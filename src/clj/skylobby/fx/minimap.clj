(ns skylobby.fx.minimap
  (:require
    [cljfx.api :as fx]
    [clojure.java.io :as io]
    clojure.set
    [clojure.string :as string]
    clojure.walk
    skylobby.fx
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed]]
    [skylobby.fx.sub :as sub]
    [spring-lobby.fs :as fs]
    [spring-lobby.fs.smf :as smf]
    [spring-lobby.spring :as spring]
    [spring-lobby.util :as u]
    [taoensso.tufte :as tufte])
  (:import
    (javafx.scene.paint Color)
    (javafx.scene.text Font FontWeight)))


(set! *warn-on-reflection* true)


(def minimap-sizes
  [256 384 512])
(def default-minimap-size
  (last minimap-sizes))


(defn minimap-start-boxes
  [scale minimap-width minimap-height scripttags drag-allyteam]
  (let [game (get scripttags "game")
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
             (fn [[id {:strs [startrectbottom startrectleft startrectright startrecttop]}]]
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
           :x (int (min startx endx))
           :y (int (min starty endy))
           :width (int (Math/abs (double (- endx startx))))
           :height (int (Math/abs (double (- endy starty))))})))))

(defn get-teams [players]
  (->> players
       (filter (comp :mode :battle-status))
       (map (juxt :username identity))
       (into {})))

(defn minimap-starting-points
  [teams map-details scripttags scale minimap-width minimap-height]
  (let [{:keys [map-width map-height]} (-> map-details :smf :header)
        team-by-key (->> teams
                         (map second)
                         (map (juxt (comp spring/team-str spring/team-name :id :battle-status) identity))
                         (into {}))
        map-teams (clojure.walk/stringify-keys (spring/map-teams map-details))
        missing-teams (clojure.set/difference
                        (set (map spring/team-str (spring/team-keys teams)))
                        (set (map (comp spring/team-str first) map-teams)))
        midx (if map-width (quot (* spring/map-multiplier map-width) 2) 0)
        midz (if map-height (quot (* spring/map-multiplier map-height) 2) 0)
        choose-before-game (= "3" (str (get-in scripttags ["game" "startpostype"])))
        all-teams (if choose-before-game
                    (concat map-teams (map (fn [team] [team {}]) missing-teams))
                    map-teams)]
    (when (and (number? map-width)
               (number? map-height)
               (number? minimap-width)
               (number? minimap-height))
      (->> all-teams
           (map
             (fn [[team-kw {:strs [startpos]}]]
               (let [{:strs [x z]} startpos
                     [_all team] (re-find #"(\d+)" (name team-kw))
                     normalized (spring/team-str team-kw)
                     scriptx (when choose-before-game
                               (some-> (get-in scripttags ["game" normalized "startposx"]) u/to-number))
                     scriptz (when choose-before-game
                               (some-> (get-in scripttags ["game" normalized "startposz"]) u/to-number))
                     scripty (when choose-before-game
                               (some-> (get-in scripttags ["game" normalized "startposy"]) u/to-number))
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
                    :color (or (some-> team-by-key (get normalized) :team-color u/spring-color-to-javafx)
                               Color/WHITE)}))))
           (filter some?)
           doall))))

(defn minimap-pane-impl
  [{:fx/keys [context]
    :keys [players map-name minimap-type-key scripttags server-key]}]
  (let [
        am-host (fx/sub-ctx context sub/am-host server-key)
        am-spec (fx/sub-ctx context sub/am-spec server-key)
        channel-name (fx/sub-ctx context skylobby.fx/battle-channel-sub server-key)
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        drag-team (fx/sub-val context :drag-team)
        drag-allyteam (fx/sub-val context :drag-allyteam)
        battle-id (fx/sub-val context get-in [:by-server server-key :battle :battle-id])
        increment-ids (fx/sub-val context :increment-ids)
        map-name (or map-name
                     (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-map]))
        spring-root (fx/sub-ctx context sub/spring-root server-key)
        indexed-map (fx/sub-ctx context sub/indexed-map spring-root map-name)
        map-details (fx/sub-ctx context skylobby.fx/map-details-sub indexed-map)
        scripttags (or scripttags
                       (fx/sub-val context get-in [:by-server server-key :battle :scripttags]))
        scripttags (clojure.walk/stringify-keys scripttags)
        minimap-size (fx/sub-val context :minimap-size)
        minimap-type (fx/sub-val context minimap-type-key)
        {:keys [smf]} map-details
        {:keys [minimap-height minimap-width]
         :or {minimap-height smf/minimap-display-size
              minimap-width smf/minimap-display-size}} smf
        max-width-or-height (max minimap-width minimap-height)
        minimap-size (or (u/to-number minimap-size)
                         default-minimap-size)
        minimap-scale (/ (* 1.0 minimap-size) max-width-or-height)
        teams (if players
                (get-teams players)
                (when server-key
                  (spring/teams
                    (spring/battle-details {:battle (fx/sub-val context get-in [:by-server server-key :battle])}))))
        starting-points (minimap-starting-points teams map-details scripttags minimap-scale minimap-width minimap-height)
        start-boxes (minimap-start-boxes minimap-scale minimap-width minimap-height scripttags drag-allyteam)
        startpostype (spring/startpostype-name (get-in scripttags ["game" "startpostype"]))
        singleplayer (= server-key :local)
        cached-minimap-updated (fx/sub-val context get-in [:cached-minimap-updated (fs/canonical-path (:file map-details))])]
    {:fx/type :stack-pane
     :style
     {:-fx-min-width minimap-size
      :-fx-max-width minimap-size
      :-fx-min-height minimap-size
      :-fx-max-height minimap-size}
     :on-scroll {:event/type :spring-lobby/minimap-scroll
                 :minimap-type-key minimap-type-key}
     :children
     [{:fx/type :v-box
       :alignment :center
       :children
       [
        {:fx/type :label
         :text (str map-name)
         :style {:-fx-font-size 16}}
        {:fx/type :label
         :text (if (seq map-details)
                 "(no image)"
                 "(loading...)")
         :alignment :center}]}
      {:fx/type ext-recreate-on-key-changed
       :key (or cached-minimap-updated 0)
       :desc
       {:fx/type :image-view
        :image {:url (-> map-name (fs/minimap-image-cache-file {:minimap-type minimap-type}) io/as-url str)
                :background-loading true}
        :fit-width minimap-size
        :fit-height minimap-size
        :preserve-ratio true}}
      (merge
        (when (or singleplayer (not am-spec))
          {:on-mouse-pressed {:event/type :spring-lobby/minimap-mouse-pressed
                              :minimap-scale minimap-scale
                              :startpostype startpostype
                              :starting-points starting-points
                              :start-boxes start-boxes}
           :on-mouse-dragged {:event/type :spring-lobby/minimap-mouse-dragged}
           :on-mouse-released {:event/type :spring-lobby/minimap-mouse-released
                               :am-host am-host
                               :am-spec am-spec
                               :channel-name channel-name
                               :client-data client-data
                               :map-details map-details
                               :minimap-scale minimap-scale
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
                                (set (map str (take (count teams) (iterate inc 0)))))
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
                       text (if random "?"
                              (if increment-ids
                                (when-let [n (u/to-number team)]
                                  (inc n))
                                team))
                       xc (- x (if (= 1 (count (str text))) ; single digit
                                 (* u/start-pos-r -0.6)
                                 (* u/start-pos-r -0.2)))
                       yc (+ y (/ u/start-pos-r 0.70))
                       fill-color (if random Color/RED color)]
                   (.setLineWidth gc 2.0)
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
                   (.setLineWidth gc 3.0)
                   (.strokeText gc (str text) xc yc)
                   (.setFill gc Color/WHITE)
                   (.fillText gc (str text) xc yc)))
               (doseq [{:keys [allyteam x y width height]} start-boxes]
                 (when (and x y width height (not= 0 width) (not= 0 height))
                   (let [color (Color/color 0.5 0.5 0.5 0.5)
                         border 4
                         text (if increment-ids
                                (inc (u/to-number allyteam))
                                allyteam)
                         font-size 20.0
                         xt (+ x (quot font-size 2))
                         yt (+ y (* font-size 1.2))]
                     (.setLineWidth gc 2.0)
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
                     (.setLineWidth gc 5.0)
                     (.strokeText gc (str text) xt yt)
                     (.setFill gc Color/WHITE)
                     (.fillText gc (str text) xt yt)
                     (when (or singleplayer (not am-spec))
                       (.setFill gc Color/BLACK)
                       (.fillText gc "x" (- (+ x width) font-size) yt))))))))})]}))


(defn minimap-pane [state]
  (tufte/profile {:dyanmic? true
                  :id :skylobby/ui}
    (tufte/p :minimap-pane
      (minimap-pane-impl state))))
