# alt-spring-lobby ![test](https://github.com/skynet-gh/alt-spring-lobby/workflows/test/badge.svg)

A [Spring RTS](https://springrts.com/) lobby for [uberserver](https://github.com/spring/uberserver).


## Install

You can download a platform installer on the [releases page](https://github.com/skynet-gh/alt-spring-lobby/releases/latest). Or, you can use one of the standalone jar files there, and you'll need to [install a recent Java version](https://adoptopenjdk.net/?variant=openjdk15) as well.


## Build


You will need the [Clojure CLI tools](https://clojure.org/guides/getting_started#_clojure_installer_and_cli_tools).

To build an executable jar file, run

```bash
clojure -M:uberjar
```

And then run it with

```bash
java -jar alt-spring-lobby.jar
```

To build an installer, then run `jpackage` for your platform, for example on Windows

```bash
jpackage @jpackage/common @jpackage/windows
```


## Development

You will also need `rlwrap`. Run

```bash
clj -M:nrepl
```

which will start an interactive compiler as well as the UI. If you make a change to something in `src/clj` it will trigger a recompile and re-render the UI from the running state.

Logs are written to `repl.log`.

## TODO

[See the Changelog](./CHANGELOG.md) for history and some future plans.
