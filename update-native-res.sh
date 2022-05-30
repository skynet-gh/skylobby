#!/bin/bash

jar="target/skylobby.jar"

if [[ ! -f "$jar" ]]
then
  ./build-jar.sh
fi

wget -N https://github.com/skynet-gh/clojure-native-image-agent/releases/download/v0.2.0%2Bfix-noclass%2Bcustom-ignore/clojure-native-image-agent.jar

config="native-res/linux/META-INF/native-image/skylobby"

"$GRAALVM_HOME/bin/java" \
  -javaagent:clojure-native-image-agent.jar=initialize-class=skylobby.main,output-dir=$config,ignore-file=agent-ignore.txt \
  -agentlib:native-image-agent=config-merge-dir=$config,config-write-period-secs=5 \
  -jar $jar
