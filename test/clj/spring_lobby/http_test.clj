(ns spring-lobby.http-test
  (:require
    [clojure.test :refer [deftest is]]
    [spring-lobby.fs :as fs]
    [spring-lobby.http :as http]))


(deftest detect-engine-branch
  (is (= "master"
         (http/detect-engine-branch "103")))
  (is (= "master"
         (http/detect-engine-branch "104.0")))
  (is (= "maintenance"
         (http/detect-engine-branch "104.0.1-1560-g50390f6 maintenance")))
  (is (= "develop"
         (http/detect-engine-branch "104.0.1-2141-gfb2f9d5 develop"))))

(deftest engine-url
  (is (= "https://springrts.com/dl/buildbot/default/maintenance/104.0.1-1560-g50390f6/linux64/spring_{maintenance}104.0.1-1560-g50390f6_minimal-portable-linux64-static.7z"
         (with-redefs [fs/sys-data (constantly {:os-name "Linux"
                                                :os-version ""})]
           (http/engine-url "104.0.1-1560-g50390f6 maintenance"))))
  (is (= "https://springrts.com/dl/buildbot/default/develop/104.0.1-2141-gfb2f9d5/linux64/spring_{develop}104.0.1-2141-gfb2f9d5_minimal-portable-linux64-static.7z"
         (with-redefs [fs/sys-data (constantly {:os-name "Linux"
                                                :os-version ""})]
           (http/engine-url "104.0.1-2141-gfb2f9d5 develop"))))
  (is (= "https://springrts.com/dl/buildbot/default/develop/104.0.1-2141-gfb2f9d5/win32/spring_{develop}104.0.1-2141-gfb2f9d5_win32-minimal-portable.7z"
         (with-redefs [fs/sys-data (constantly {:os-name "Windows"})]
           (http/engine-url "104.0.1-2141-gfb2f9d5 develop")))))

(deftest map-url
  (is (= "http://api.springfiles.com/files/maps/pentos_v1.sd7"
         (http/map-url "Pentos_V1"))))
