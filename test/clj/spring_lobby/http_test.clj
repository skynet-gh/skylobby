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

(deftest engine-archive
  (is (= "spring_{maintenance}104.0.1-1560-g50390f6_minimal-portable-linux64-static.7z"
         (with-redefs [fs/get-sys-data (constantly {:os-name "Linux"
                                                    :os-version ""})]
           (http/engine-archive "104.0.1-1560-g50390f6 maintenance"))))
  (is (= "spring_{develop}104.0.1-2141-gfb2f9d5_minimal-portable-linux64-static.7z"
         (with-redefs [fs/get-sys-data (constantly {:os-name "Linux"
                                                    :os-version ""})]
           (http/engine-archive "104.0.1-2141-gfb2f9d5 develop"))))
  (is (= "spring_{develop}104.0.1-2141-gfb2f9d5_win32-minimal-portable.7z"
         (with-redefs [fs/get-sys-data (constantly {:os-name "Windows"})]
           (http/engine-archive "104.0.1-2141-gfb2f9d5 develop")))))

(deftest engine-archive?
  (is (true?
        (http/engine-archive? "spring_{maintenance}104.0.1-1560-g50390f6_minimal-portable-linux64-static.7z")))
  (is (true?
        (http/engine-archive? "spring_{develop}104.0.1-2141-gfb2f9d5_minimal-portable-linux64-static.7z")))
  (is (true?
        (http/engine-archive? "spring_{develop}104.0.1-2141-gfb2f9d5_win32-minimal-portable.7z")))
  (is (false?
        (http/engine-archive? "{maintenance}104.0.1-1563-g66cad77_win32_UnitTests.7z")))
  (is (false?
        (http/engine-archive? "spring_{maintenance}104.0.1-1563-g66cad77_win32_portable.7z")))
  (is (true?
        (http/engine-archive? "spring_104.0_win32-minimal-portable.7z")))
  (is (true?
        (http/engine-archive? "spring_104.0_minimal-portable-linux64-static.7z")))
  (is (false?
        (http/engine-archive? "104.0_spring_dbg.7z"))))


(deftest bar-engine-filename
  (is (= "spring_bar_.BAR.104.0.1-1656-gad7994a_linux-64-minimal-portable.7z",
         (with-redefs [fs/get-sys-data (constantly {:os-name "Linux"})]
           (http/bar-engine-filename "104.0.1-1656-gad7994a BAR"))))
  (is (= "spring_bar_.BAR.104.0.1-1695-gbd6b256_windows-32-minimal-portable.7z",
         (with-redefs [fs/get-sys-data (constantly {:os-name "Windows"})]
           (http/bar-engine-filename "104.0.1-1695-gbd6b256 BAR")))))

(deftest bar-engine-filename?
  (is (false?
        (http/bar-engine-filename?
          "spring_bar_.BAR.104.0.1-1656-gad7994a_windows-64-minimal-symbols.tgz",)))
  (is (true?
        (http/bar-engine-filename?
          "spring_bar_.BAR.104.0.1-1656-gad7994a_windows-64-minimal-portable.7z",))))
