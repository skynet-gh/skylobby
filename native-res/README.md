# skylobby native resources

Resources used by GraalVM native image.

## Prerequisites

You will need

- [Clojure CLI tools](https://clojure.org/guides/getting_started) 1.10.3.933 or later
- [GraalVM](https://github.com/graalvm/graalvm-ce-builds/releases/tag/vm-21.3.0) with native-image installed and `$GRAALVM_HOME` set
- [clojure-native-image-agent jar](https://github.com/luontola/clojure-native-image-agent/releases)

## Generating

Clean previous runs

```
clj -T:skylobby-cli clean
```

Build the jar

```
clj -T:skylobby-cli uber
```

You should delete the existing configs, otherwise you will have leftovers from previous runs that might not be needed:

```
rm native-res/META-INF/native-image/skylobby/*
```

Now run the jar with the [clojure-native-image-agent](https://github.com/luontola/clojure-native-image-agent) and the GraalVM native-image-agent. This example downloads and extracts a Spring engine, so it executes 7zip, which is required in order to build the native image properly:

```
$GRAALVM_HOME/bin/java -javaagent:clojure-native-image-agent.jar=initialize-class=skylobby.resource.main,output-dir=native-res/META-INF/native-image/skylobby -agentlib:native-image-agent=config-merge-dir=native-res/META-INF/native-image/skylobby -jar target/skyres-0.0.1-standalone.jar get 105.0
```

You should now see the GraalVM config files populated in `native-res`. You can now build the native image:

```
clj -M:skylobby-cli-native
```
