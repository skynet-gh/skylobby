# skylobby native resources

Resources used by GraalVM native image.

## Prerequisites

You will need

- [Clojure CLI tools](https://clojure.org/guides/getting_started) 1.10.3.933 or later
- [GraalVM Java 11](https://github.com/graalvm/graalvm-ce-builds/releases/tag/vm-21.3.0) with native-image installed and `$GRAALVM_HOME` set
- [clojure-native-image-agent jar](https://github.com/luontola/clojure-native-image-agent/releases)

### Windows

You will need the [Microsoft Visual Studio C/C++ Build Tools](https://visualstudio.microsoft.com/visual-cpp-build-tools/) and install at least these components:

- MSVC v142 - VS 2019 C++ x64/x64 build tools libs (latest)
- MSVC v142 - VS 2019 C++ x64/x64 Spectre-mitigated libs (latest)
- C++/CLI support for v142 build tools (latest)
- Windows 10 SDK

Then you will need to configure your environment variables:

```
INCLUDE=C:\Program Files (x86)\Windows Kits\10\Include\10.0.20348.0\ucrt;C:\Program Files (x86)\Windows Kits\10\Include\10.0.20348.0\um;C:\Program Files (x86)\Windows Kits\10\Include\10.0.20348.0\shared;C:\Program Files (x86)\Microsoft Visual Studio\2019\BuildTools\VC\Tools\MSVC\14.29.30133\include;
LIB=C:\Program Files (x86)\Windows Kits\10\Lib\10.0.20348.0\um\x64;C:\Program Files (x86)\Windows Kits\10\Lib\10.0.20348.0\ucrt\x64;C:\Program Files (x86)\Microsoft Visual Studio\2019\BuildTools\VC\Tools\MSVC\14.29.30133\lib\x64;
LIBPATH=C:\Program Files (x86)\Windows Kits\10\Lib\10.0.20348.0\um\x64;C:\Program Files (x86)\Windows Kits\10\Lib\10.0.20348.0\ucrt\x64;C:\Program Files (x86)\Microsoft Visual Studio\2019\BuildTools\VC\Tools\MSVC\14.29.30133\lib\x64;
Path=...;C:\Program Files (x86)\Microsoft Visual Studio\2019\BuildTools\VC\Tools\MSVC\14.29.30133\bin\Hostx64\x64
```

Replace the versions with the ones on your system.

## Generating

Clean previous runs

```
clj -T:skylobby-cli clean
```

Build the jar

```
clj -T:skylobby-cli uber
```

### Linux

You should delete the existing configs, otherwise you will have leftovers from previous runs that might not be needed:

```
rm native-res/linux/META-INF/native-image/skylobby/*
```

Now run the jar with the [clojure-native-image-agent](https://github.com/luontola/clojure-native-image-agent) and the GraalVM native-image-agent. This example downloads and extracts a Spring engine, so it executes 7zip, which is required in order to build the native image properly:

```
$GRAALVM_HOME/bin/java -javaagent:clojure-native-image-agent.jar=initialize-class=skylobby.cli,output-dir=native-res/linux/META-INF/native-image/skylobby -agentlib:native-image-agent=config-merge-dir=native-res/linux/META-INF/native-image/skylobby -jar target/skylobby-cli-0.0.1-standalone.jar get 105.0
```

You should now see the GraalVM config files populated in `native-res`. You can now build the native image:

```
clj -M:skylobby-cli-deps:skylobby-cli-native-linux
```

### Windows

You should delete the existing configs, otherwise you will have leftovers from previous runs that might not be needed:

```
rm native-res/windows/META-INF/native-image/skylobby/*
```

Now run the jar with the [clojure-native-image-agent](https://github.com/luontola/clojure-native-image-agent) and the GraalVM native-image-agent. This example downloads and extracts a Spring engine, so it executes 7zip, which is required in order to build the native image properly:

```
& "$env:GRAALVM_HOME\bin\java" -javaagent:clojure-native-image-agent.jar=initialize-class=skylobby.cli,output-dir=native-res\windows\META-INF\native-image\skylobby -agentlib:native-image-agent=config-merge-dir=native-res\windows\META-INF\native-image\skylobby -jar target\skylobby-cli-0.0.1-standalone.jar get 105.0
```

You should now see the GraalVM config files populated in `native-res`. You can now build the native image:

```
clj -M:skylobby-cli-deps:skylobby-cli-native-windows
```
