(ns skylobby.color
  (:require
    [cljfx.coerce :as coerce]
    [skylobby.fx.color :as fx.color])
  (:import
    (javafx.scene.paint Color)))


(set! *warn-on-reflection* true)


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
(def colors-11v11
  [["0xcc0000ff"
    "0xd900b5ff"
    "0xe67a00ff"
    "0xffff00ff"
    "0xff6666ff"
    "0xff80eaff"
    "0xffb259ff"
    "0xbaba00ff"
    "0xa50000ff"
    "0xab5a00ff"
    "0xa5008aff"]
   ["0x0049dbff"
    "0x26dbffff"
    "0x00d9a3ff"
    "0x40ff00ff"
    "0x6699ffff"
    "0x0095b3ff"
    "0x008060ff"
    "0x29a600ff"
    "0x7faaffff"
    "0x006a7fff"
    "0x003bb2ff"]])
(def colors-4-team-ffa
  [["0xff0000ff"
    "0xd800b4ff"
    "0xff7272ff"
    "0xff7feaff"
    "0xa50000ff"]
   ["0x0054ffff"
    "0x26daffff"
    "0x7faaffff"
    "0x0094b2ff"
    "0x003bb2ff"]
   ["0x3aec00ff"
    "0x00d8a3ff"
    "0xa5ff86ff"
    "0x007f5fff"
    "0x28a500ff"]
   ["0xffff00ff"
    "0xff8700ff"
    "0xb9b900ff"
    "0xffbe72ff"
    "0xab5a00ff"]])

(def bar-team-colors
  [["rgb(0, 80, 255)"
    "rgb(10, 232, 18)"
    "rgb(147, 226, 251)"
    "rgb(41, 166, 176)"
    "rgb(191, 169, 255)"
    "rgb(0, 170, 99)"
    "rgb(117, 253, 147)"
    "rgb(39, 63, 84)"
    "0x7faaffff"
    "0x006a7fff"
    "0x28a500ff"
    "0x003bb2ff"]
   ["rgb(255, 16, 5)"
    "rgb(255, 232, 22)"
    "rgb(255, 125, 32)"
    "rgb(229, 18, 120)"
    "rgb(255, 243, 135)"
    "rgb(72, 9, 24)"
    "rgb(251, 167, 120)"
    "rgb(118, 39, 6)"
    "0xa50000ff"
    "0xb9b900ff"
    "0xab5a00ff"
    "0xa5008aff"]])


(def team-colors-by-min-size
  {5 colors-11v11
   4 colors-4v4
   3 colors-3v3
   2 colors-2v2
   1 colors-1v1})

(def colors-by-team-count
  {2 colors-11v11
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
   colors-4-team-ffa
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
    (fn [c] (fx.color/javafx-color-to-spring (Color/web c)))
    ffa-colors-web))


(defn player-color [{:keys [ally id]} teams-by-allyteam]
  (when-let [^java.util.List allyteam-players (get teams-by-allyteam ally)]
    (let [
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
        (fx.color/javafx-color-to-spring
          (Color/web web-color))))))


; https://github.com/beyond-all-reason/Beyond-All-Reason/blob/5572edc/luaui/Widgets/gui_advplayerslist.lua#L1524
(defn dark?
  "Returns true if the given color is considered dark, false otherwise."
  [c]
  (let [^Color color (coerce/color c)]
    (< (+ (* 1.2 (+ (.getRed color)
                    (.getGreen color)))
          (* 0.4 (.getBlue color)))
       0.68)))
