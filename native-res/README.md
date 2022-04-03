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

## Linux

First build the jar with

```
./build-jar.sh
```

Now run the jar with the [clojure-native-image-agent](https://github.com/luontola/clojure-native-image-agent) 
and the GraalVM native-image-agent. Run with:

```
./update-native-res.sh <args>
```

Now use the program in order to reach areas you need to configure. Repeat this process for 
different arguments if needed.

You should now see the GraalVM config files populated in `native-res`. You can now build the native 
image:

```
clj -M:graal-deps:native-linux
```

## Windows

First build the jar with

```
./build-jar.ps1
```


Now run the jar with the [clojure-native-image-agent](https://github.com/luontola/clojure-native-image-agent)
and the GraalVM native-image-agent. Run with:

```
./update-native-res.ps1 <args>
```

Now use the program in order to reach areas you need to configure. Repeat this process for 
different arguments if needed.

You should now see the GraalVM config files populated in `native-res`. You can now build the native 
image:

```
clj -M:graal-deps:native-windows
```
