# skylobby [![test](https://github.com/skynet-gh/skylobby/actions/workflows/test.yml/badge.svg)](https://github.com/skynet-gh/skylobby/actions/workflows/test.yml) [![codecov](https://codecov.io/gh/skynet-gh/skylobby/branch/master/graph/badge.svg?token=CXO1YGKX5W)](https://codecov.io/gh/skynet-gh/skylobby) [![license](https://img.shields.io/github/license/skynet-gh/skylobby)](LICENSE)

A [Spring RTS](https://springrts.com/) lobby for [uberserver](https://github.com/spring/uberserver).

## Usage

Install instructions and basic usage can be found in the [User Guide](https://github.com/skynet-gh/skylobby/wiki/User-Guide).

Feel free to open an [issue](https://github.com/skynet-gh/skylobby/issues) if you find a bug or have a feature request.

## Dev

Below are instructions on how to build and contribute to skylobby.

You will need [Java 11 or higher](https://adoptium.net/) and the [Clojure CLI tools](https://clojure.org/guides/getting_started#_clojure_installer_and_cli_tools).

### REPL

Runs an interactive compiler as well as the UI. If you make a change to something in `src/clj` it will trigger a recompile and re-render the UI from the running state.

You will also need to install `rlwrap`. Run:

```bash
clj -M:nrepl
```

Logs are written to `repl.log`.

### Jar

To build an executable jar file:

```bash
clojure -M:uberjar
```

And then run it with:

```bash
java -jar target/skylobby.jar
```

To build an installer, then run `jpackage` for your platform, for example on Windows

```bash
jpackage @jpackage/common @jpackage/windows
```
