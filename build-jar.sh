#/bin/sh

npm install

clojure -M:cljs compile frontend

clojure -T:build clean

clojure -T:build uber
