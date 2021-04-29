(ns skylobby.fx.flag-icon
  (:require
    [taoensso.timbre :as log])
  (:import
    (griffon.javafx.support.flagicons FlagIcon)))


(set! *warn-on-reflection* true)


(defn flag-image [country-code]
  (try
    (FlagIcon. country-code)
    (catch Exception e
      (log/trace e "Error creating flag icon"))))

(def flag-image-memoized
  (memoize flag-image))


(defn flag-icon [{:keys [^String country-code]}]
  (merge
    {:fx/type :label
     :text (str country-code)}
    (when-let [image (flag-image-memoized country-code)]
      {:graphic
       {:fx/type :image-view
        :image image}})))
