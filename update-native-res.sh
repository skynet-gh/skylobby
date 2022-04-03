#/bin/sh

jar="target/skylobby.jar"

if [[ ! -f $jar ]]
then
  ./build-jar.sh
fi

wget -N https://github.com/skynet-gh/clojure-native-image-agent/releases/download/v0.2.0%2Bfix-noclass%2Bcustom-ignore/clojure-native-image-agent.jar

$GRAALVM_HOME/bin/java -javaagent:clojure-native-image-agent.jar=initialize-class=skylobby.main,output-dir=native-res/linux/META-INF/native-image/skylobby,ignore-file=agent-ignore.txt -agentlib:native-image-agent=config-merge-dir=native-res/linux/META-INF/native-image/skylobby,config-write-period-secs=5 -jar $jar
