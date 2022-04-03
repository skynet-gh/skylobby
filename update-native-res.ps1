
& "$env:GRAALVM_HOME\bin\java" -javaagent:clojure-native-image-agent.jar=initialize-class=skylobby.main,output-dir=native-res\windows\META-INF\native-image\skylobby,ignore-file=agent-ignore.txt -agentlib:native-image-agent=config-merge-dir=native-res\windows\META-INF\native-image\skylobby,config-write-period-secs=5 -jar target\skylobby.jar $args
