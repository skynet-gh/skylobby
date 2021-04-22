(ns skylobby.color)


; taken from SPADS !fixcolors in an 8v8
(def allyteam-colors-spring
  {1
   ["6324224"
    "65344"
    "11769088"
    "42537"
    "16750950"
    "16767782"
    "14371072"
    "10737920"]
   0
   ["65535"
    "6711039"
    "11862233"
    "31462"
    "15368447"
    "5878527"
    "47802"
    "204"
    "16777215"]})

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
