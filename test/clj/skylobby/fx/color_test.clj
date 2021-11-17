(ns skylobby.fx.color-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.fx.color :as fx.color])
  (:import
    (javafx.scene.paint Color)))


(deftest spring-color-to-javafx
  (is (= "0x000000ff"
         (str
           (fx.color/spring-color-to-javafx nil))))
  (is (= "0x393000ff"
         (str
           (fx.color/spring-color-to-javafx 12345)))))

(deftest javafx-color-to-spring
  (is (= 0
         (fx.color/javafx-color-to-spring nil)))
  (is (= 12345
         (fx.color/javafx-color-to-spring (Color/web "0x393000ff")))))
