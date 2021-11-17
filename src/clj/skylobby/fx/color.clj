(ns skylobby.fx.color
  (:require
    [com.evocomputing.colors :as colors]
    [skylobby.util :as u])
  (:import
    (javafx.scene.paint Color)))


(defn spring-color-to-javafx
  "Returns the rgb int color represention for the given Spring bgr int color."
  [spring-color]
  (let [spring-color-int (or (u/to-number spring-color) 0)
        [r g b _a] (:rgba (colors/create-color spring-color-int))
        reversed (colors/create-color
                   {:r b
                    :g g
                    :b r})]
    (Color/web (format "#%06x" (colors/rgb-int reversed)))))

(defn javafx-color-to-spring
  "Returns the spring bgr int color format from a javafx color."
  [^Color color]
  (if color
    (colors/rgba-int
      (colors/create-color
        {:r (Math/round (* 255 (.getBlue color)))  ; switch blue to red
         :g (Math/round (* 255 (.getGreen color)))
         :b (Math/round (* 255 (.getRed color)))   ; switch red to blue
         :a 0}))
    0))
