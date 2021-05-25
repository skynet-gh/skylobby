(ns skylobby.color
  (:require
    [spring-lobby.util :as u])
  (:import
    (javafx.scene.paint Color)))


; taken from SPADS !fixcolors
(def colors-coop-ai
  ["0xf700ffff"
   "0x8000ffff"
   "0xd9007eff"
   "0xfb8cffff"
   "0xbf80ffff"
   "0xff59baff"
   "0xa000a6ff"
   "0x6100a6ff"])
(def colors-1v1
   [["0xcc0000ff"]
    ["0x0055ffff"]])
(def colors-2v2
  [["0xff0000ff" "0xffff00ff"]
   ["0x0055ffff" "0x00ff00ff"]])
(def colors-3v3
  [["0xff0000ff"
    "0xff00ffff"
    "0xffff00ff"]
   ["0x0055ffff"
    "0x00ffffff"
    "0x00ff00ff"]])
(def colors-4v4
  [["0xff0000ff"
    "0xff00d4ff"
    "0xff8800ff"
    "0xf2f200ff"]
   ["0x1966ffff"
    "0x00d5ffff"
    "0x00b386ff"
    "0x3df200ff"]])
(def colors-8v8
  [["0xcc0000ff"
    "0xd900b5ff"
    "0xe67a00ff"
    "0xffff00ff"
    "0xff6666ff"
    "0xff80eaff"
    "0xffb259ff"
    "0xbaba00ff"]
   ["0x0049dbff"
    "0x26dbffff"
    "0x00d9a3ff"
    "0x40ff00ff"
    "0x6699ffff"
    "0x0095b3ff"
    "0x008060ff"
    "0x29a600ff"]])

(def team-colors-by-min-size
  {5 colors-8v8
   4 colors-4v4
   3 colors-3v3
   2 colors-2v2
   1 colors-1v1})

(def colors-by-team-count
  {2 colors-8v8
   3
   [["0xcc0000ff"
     "0xd900b5ff"
     "0xe67a00ff"
     "0xff6666ff"
     "0xff80eaff"
     "0xffb259ff"]
    ["0x0049dbff"
     "0x7400d9ff"
     "0x26dbffff"
     "0x6699ffff"
     "0xb866ffff"
     "0x0095b3ff"]
    ["0x008060ff"
     "0x40ff00ff"
     "0x00d9a3ff"
     "0xf2f200ff"
     "0x29a600ff"]]
   4
   [["0xff0000ff"
     "0xd900b5ff"
     "0xff7373ff"
     "0xff80eaff"
     "0xa60000ff"]
    ["0x0049dbff"
     "0x26dbffff"
     "0x6699ffff"
     "0x0095b3ff"]
    ["0x40ff00ff"
     "0x00d9a3ff"
     "0x29a600ff"
     "0x008060ff"]
    ["0xffb259ff"
     "0xffff00ff"
     "0xe67a00ff"
     "0xbaba00ff"]]
   5
   [["0xcc0000ff"
     "0xd900b5ff"
     "0xff6666ff"
     "0xff80eaff"]
    ["0x0049dbff"
     "0x26dbffff"
     "0x6699ffff"
     "0x0095b3ff"]
    ["0x3bed00ff"
     "0xa5ff87ff"
     "0x29a600ff"]
    ["0xffff26ff"
     "0xc7c700ff"
     "0x808000ff"]
    ["0x5f00b3ff"
     "0xa033ffff"
     "0xc98cffff"]]})


(def ffa-colors-web
  ["0xcc0000ff"
   "0x0000ccff"
   "0x00ff00ff"
   "0xffff00ff"
   "0x00eaffff"
   "0xf700ffff"
   "0xff7300ff"
   "0x8000ffff"
   "0x00b377ff"
   "0xffbf00ff"
   "0x0095ffff"
   "0xd91687ff"
   "0x88cc00ff"
   "0xff6666ff"
   "0x6666ffff"
   "0x00a600ff"])


(def ffa-colors-spring
  (mapv
    (fn [c] (u/javafx-color-to-spring (Color/web c)))
    ffa-colors-web))


(defn player-color [{:keys [ally id]} teams-by-allyteam]
  (let [allyteam-players (get teams-by-allyteam ally)
        player-index (.indexOf allyteam-players id)
        team-count (count teams-by-allyteam)
        web-color (or
                    (get
                      (get
                        (if (= 2 team-count)
                          (let [max-team-size (reduce (fnil max 0) 0 (map count (vals teams-by-allyteam)))]
                            (reduce
                              (fn [chosen [min-team-size colors]]
                                (or chosen
                                  (when (<= min-team-size max-team-size)
                                    colors)))
                              nil
                              team-colors-by-min-size))
                          (get colors-by-team-count team-count))
                        ally)
                      player-index)
                    (get ffa-colors-web id))]
    (when web-color
      (u/javafx-color-to-spring
        (Color/web web-color)))))


; https://github.com/beyond-all-reason/Beyond-All-Reason/blob/5572edc/luaui/Widgets/gui_advplayerslist.lua#L1524
(defn dark?
  "Returns true if the given color is considered dark, false otherwise."
  [^javafx.scene.paint.Color color]
  (< (+ (* 1.2 (+ (.getRed color)
                  (.getGreen color)))
        (* 0.4 (.getBlue color)))
     0.68))
