(ns skylobby.color
  (:require
    [spring-lobby.util :as u])
  (:import
    (javafx.scene.paint Color)))


; taken from SPADS !fixcolors in an 8v8
(def allyteam-colors-web
  {1
   ["0x0048daff"
    "0x26daffff"
    "0x00d8a3ff"
    "0x3fff00ff"
    "0x6699ffff"
    "0x0094b2ff"
    "0x007f5fff"
    "0x28a500ff"],
   0
   ["0xcc0000ff"
    "0xd800b4ff"
    "0xe57900ff"
    "0xffff00ff"
    "0xff6666ff"
    "0xff7feaff"
    "0xffb259ff"
    "0xb9b900ff"]})

(def allyteam-colors-spring
  (->> allyteam-colors-web
       (map (fn [[k vs]]
              [k (mapv (fn [c] (u/javafx-color-to-spring (Color/web c))) vs)]))
       (into {})))


(defn team-color [{:keys [id ally]}]
  (-> allyteam-colors-spring
      (get ally)
      (get (mod id 8))))

; https://github.com/beyond-all-reason/Beyond-All-Reason/blob/5572edc/luaui/Widgets/gui_advplayerslist.lua#L1524
(defn dark?
  "Returns true if the given color is considered dark, false otherwise."
  [^javafx.scene.paint.Color color]
  (< (+ (* 1.2 (+ (.getRed color)
                  (.getGreen color)))
        (* 0.4 (.getBlue color)))
     0.68))
