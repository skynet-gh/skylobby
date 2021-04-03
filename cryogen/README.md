# Repo docs on gh-pages using Cryogen

[GitHub Pages for this repo](https://skynet-gh/skylobby/) using [Cryogen](https://github.com/cryogen-project/cryogen).

### Dev

To run a local server, run

```
lein serve
```

A browser should be opened to http://localhost:3000/skylobby

### Build

GitHub actions now publishes content to the `gh-pages` branch automatically. To build manually, run

```
clj -M:build
```
