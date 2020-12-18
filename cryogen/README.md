# Repo docs on gh-pages using Cryogen

[GitHub Pages for this repo](https://skynet-gh/alt-spring-lobby/) using [Cryogen](https://github.com/cryogen-project/cryogen).

### Dev

To run a local server, run

```
lein serve
```

A browser should be opened to http://localhost:3000/alt-spring-lobby

### Build

Until GitHub actions are working, build with

```
clj -M:build
```

Then, assuming you have another repo at `../asl-cryogen` checked out to the `gh-pages` branch, copy
into place with

```
cp public/* ../asl-cryogen/
```

And commit and push changes.
