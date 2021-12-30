(ns skylobby.fx-test
  (:require
    [clojure.test :refer [deftest is]]
    skylobby.fx))


(set! *warn-on-reflection* true)


(def screen-bounds
  {:min-x -2560.0
   :min-y 0.0
   :max-x 2498.0
   :max-y 1440.0
   :width 5058.0
   :height 1440.0})


(deftest fitx
  (is (= 0
         (with-redefs [skylobby.fx/get-screen-bounds (constantly nil)]
           (skylobby.fx/fitx nil))))
  (is (= -2560.0
         (with-redefs [skylobby.fx/get-screen-bounds (constantly screen-bounds)]
           (skylobby.fx/fitx nil))))
  (is (= -2560.0
         (skylobby.fx/fitx screen-bounds))))
(deftest fity
  (is (= 0
         (with-redefs [skylobby.fx/get-screen-bounds (constantly nil)]
           (skylobby.fx/fity nil))))
  (is (= 0.0
         (with-redefs [skylobby.fx/get-screen-bounds (constantly screen-bounds)]
           (skylobby.fx/fity nil))))
  (is (= 0.0
         (skylobby.fx/fity screen-bounds))))
(deftest fitwidth
  (is (= 256
         (with-redefs [skylobby.fx/get-screen-bounds (constantly nil)]
           (skylobby.fx/fitwidth nil -10000))))
  (is (= 256
         (with-redefs [skylobby.fx/get-screen-bounds (constantly screen-bounds)]
           (skylobby.fx/fitwidth nil -10000))))
  (is (= 256
         (skylobby.fx/fitwidth screen-bounds -10000))))
(deftest fitheight
  (is (= 256
         (with-redefs [skylobby.fx/get-screen-bounds (constantly nil)]
           (skylobby.fx/fitheight nil -10000))))
  (is (= 256
         (with-redefs [skylobby.fx/get-screen-bounds (constantly screen-bounds)]
           (skylobby.fx/fitheight nil -10000))))
  (is (= 256
         (skylobby.fx/fitheight screen-bounds -10000))))
