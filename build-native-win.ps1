npm install

clojure -M:cljs compile frontend

clojure -T:build clean

clojure -M:graal-deps:native-windows
