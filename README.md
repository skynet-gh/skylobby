# alt-spring-lobby

A [Spring RTS](https://springrts.com/) lobby for [uberserver](https://github.com/spring/uberserver).

You will need the [Clojure CLI tools](https://clojure.org/guides/getting_started#_clojure_installer_and_cli_tools).


## Build


To build an executable jar file, run

```bash
clojure -Spom
clojure -M:uberjar
```

And then run it with

```bash
java -jar alt-spring-lobby.jar
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
