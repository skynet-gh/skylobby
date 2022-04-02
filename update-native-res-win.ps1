
& "$env:GRAALVM_HOME\bin\java" -javaagent:clojure-native-image-agent.jar=initialize-class=skylobby.main,output-dir=native-res\windows\META-INF\native-image\skylobby -agentlib:native-image-agent=config-merge-dir=native-res\windows\META-INF\native-image\skylobby -jar target\skylobby.jar %*
