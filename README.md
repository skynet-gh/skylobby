# alt-spring-lobby

A [Spring RTS](https://springrts.com/) lobby for [uberserver](https://github.com/spring/uberserver).


## Build


To build an executable jar file, run

```bash
clj -Xuberjar
```

And then run it with

```bash
java -jar alt-spring-lobby.jar
```


## Development


For now, you will need the [Clojure CLI tools](https://clojure.org/guides/getting_started#_clojure_installer_and_cli_tools). Once you have those you can run

```bash
clj -Anrepl
```

which will start an interactive compiler as well as the UI. If you make a change to something in `src/clj` it will trigger a recompile and re-render the UI from the running state.

Logs are written to `repl.log`.


## TODO

There are a number of things left before this is usable

- Fix path hardcoding
  - Discover Linux and Windows directories
  - Developed using WSL so this is all special
- Fix server hardcoding
- Add downloading of engines, maps, and games
- Fix mapinfo LUA execution for some maps
- Handle the rapid format somehow
- Build an executable JAR
- Make repo public
- Clean up ns organization
