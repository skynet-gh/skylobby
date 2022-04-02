npm install

clojure -M:cljs compile frontend

clojure -T:skylobby clean

clojure -M:skylobby-deps:skylobby-native-windows
