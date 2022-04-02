{:title "Journey to Native"
 :layout :page
 :page-index 2}

## Preamble

This is the second post about development, with a large time skip since [part 1](/pages/development).


Thanks to all users, bug reporters, and supporters in every way. You've made the project successful.


## Problem

As you may or may not know, [skylobby](https://github.com/skynet-gh/skylobby) is written in Clojure
(see part 1 for some reasons why). Since Clojure is a JVM language, programs depend on the JVM. This
has a few downsides:

- it requires users to have Java installed on their machine
- that Java install must be the right version
- instead of running the program as an executable, you run `java -jar <program>`
- startup times can be slow
- the JVM has a memory overhead, and allocates more than the actual heap is using

This has been true for Java programs basically forever. Recent versions introduced
[`jpackage`](https://docs.oracle.com/en/java/javase/14/docs/specs/man/jpackage.html) which allows
you to build installers that bundle a JVM just for your program, which solves the first two points
above, but startup times are still slow, it adds a cumbersome install, and is still not very
configurable.

## Solution

Enter [GraalVM](https://www.graalvm.org/) and especially its
[`native-image`](https://www.graalvm.org/22.0/reference-manual/native-image/) tool. `native-image`
lets you build a static executable out of a Java program, with very fast startup time, no
dependencies, and lower actual memory usage.

The Clojure community has been steadily
[working towards compatibility with GraalVM](https://github.com/clj-easy/graalvm-clojure#clojure-meets-graalvm)
and it is becoming straightforward to build native images, if your app does not stray too far from
the beaten path.

Unfortunately, skylobby does stray a bit from the beaten path.

I have been slowly porting code over to the `graal` folder for a while. The first challenge was
getting the 7zip Java bindings working. This became easy once I learned of the
[native-image-agent](https://www.graalvm.org/22.0/reference-manual/native-image/Agent/) which allows
you to run your program and generate the required config for native-image. Especially useful are the
reflection and resource configurations.

The biggest challenge so far is the [TCP client](https://github.com/clj-commons/aleph#tcp) which is
required for connecting to remote servers using
[this protocol](https://springrts.com/dl/LobbyProtocol/ProtocolDescription.html). It seems that raw
TCP connections aren't especially popular in native images right now, so there were a few issues:

- the `[clojure-native-image-agent]` crashes when running a program that uses Netty (popular Java
  networking library, which Aleph is built on, which skylobby uses).
- when the agent is patched not to crash, it marks many netty classes to be initialized at build
  time, while netty libraries mark them to be initialized at runtime, causing an error.
- some Clojure community libraries use single-segment namespaces, which cause issues when trying to
  mark classes for build-time init in GraalVM native-image, since Clojure compiles functions to Java
  classes like `namespace$fn__1234`, which Java sees as a top-level package, so you would need to
  configure one-by-one instead of by package.
- the above issue needs to be fixed and then propagated to all libraries using it, so in my case:
  `primitive-math` to `byte-streams` then `aleph` and `gloss`.
- speaking of [`gloss`](https://github.com/clj-commons/gloss), there is a [function in
  there](https://github.com/clj-commons/gloss/blob/0.2.6/src/gloss/data/bytes/delimited.clj#L84-L96)
  that uses the dreaded `eval`, which causes native-image to barf, since it can't statically analyze
  it at all.

For the `clojure-native-image-agent` I opened a [PR to handle the error in Netty
programs](https://github.com/luontola/clojure-native-image-agent/pull/1), as well as [created a fork
with a custom build for myself which also allows custom ignore
files](https://github.com/skynet-gh/clojure-native-image-agent/releases/tag/v0.2.0%2Bfix-noclass%2Bcustom-ignore)
which I'll also PR to upstream when first PR is merged.

I have started submitting PRs for single-segment namespace issue as well, starting with
[`primitive-math`](https://github.com/clj-commons/primitive-math/pull/14). Each library will need to
be patched and released in order before moving to the next. So until then, I've tagged my forks:

- [primitive-math](https://github.com/skynet-gh/primitive-math/releases/tag/0.1.6%2Bfix-single-segment-nses)
- [byte-streams](https://github.com/skynet-gh/byte-streams/releases/tag/0.2.10%2Bfix-single-segment-ns)
- [gloss](https://github.com/skynet-gh/gloss/releases/tag/0.2.6%2Bupdate-byte-streams)
- [aleph](https://github.com/skynet-gh/aleph/releases/tag/0.4.7%2Bupdate-byte-streams)

With all these repos forked and tagged, I can use them directly using [git
coordinates](https://clojure.org/guides/deps_and_cli#_using_git_libraries) which makes this process
much easier than needing to publish jars in order to test.

Needed to rewrite the [gloss `match-loop` function as normal code without
`eval`](https://github.com/skynet-gh/gloss/commit/d3db3b9d7f5a39d1ae504b0c2adae1d43f18b1f0) as well.

While I was forking projects, I also patched a couple instances of compiler warnings in Clojure
1.11 in [jolby/colors](https://github.com/skynet-gh/jolby-colors/releases/tag/1.0.6%2Bexclude-abs)
and
[clojure.java-time](https://github.com/skynet-gh/clojure.java-time/releases/tag/0.3.3%2Bexclude-abs).

## Home stretch

It is now possible to configure and build a native image for the web UI version of sklobby including
the TCP client needed to connect to remote servers ([see the docs for
details](https://github.com/skynet-gh/skylobby/tree/master/native-res)). Because there is such a
hard split in GraalVM native-image compatible code right now, there is some functionality that I
still need to port. Ideally though, the only non-graal thing will be the JavaFX UI.

You can run the helper script `build-native.[sh|ps1]` to use the current config. If you need to
update the native image config you can run the `update-native-res.[sh|ps1]` script, passing in CLI
args as needed which are fed to the jar.

Startup performance improvement:

```
$ time java -jar target/skylobby.jar version
skylobby b9da7848955f463dbb379694087710c4dc9e2222

real    0m2.940s
user    0m7.072s
sys     0m1.307s

$ time ./skylobby version
skylobby b9da7848955f463dbb379694087710c4dc9e2222

real    0m0.011s
user    0m0.000s
sys     0m0.011s
```

## Web UI

I should probably talk about the web UI that is the replacement in the native image for now. May
look into using [Gluon](https://github.com/gluonhq/) to make the desktop UI available as a native
image as well, but that seems low priority. For one thing, JavaFX is a major hog when it comes to
memory, so that would limit the overall potential gains.

skylobby can't go full cloud either though, since most of the functionality involves managing local
filesystem resources and starting processes. The UI could be loaded and viewed on a remove website,
but there will still need to be a server-like process running locally.

The big change for the web UI is how to convey changing data from "server" to "client" (both
actually running on `localhost`). There is a slick library
[`sente`](https://github.com/ptaoussanis/sente) that allows you to open bidirectional communication
using http (which may then upgrade to websocket). This at least allows the flexibility I need, but
so far lots of keeping state in sync looks somewhat manual. So maybe there's a missing piece here in
order to communicate state changes.

## Further Memory Reductions

Currently, the index of rapid packages is stored in-memory (rapid is the package system some parts
of Spring RTS use). This index uses around 140 MB currently, and that can grow depending on how many
spring install directories you have. So it's an obvious candidate for moving into some sort of file
storage or database. A database seems needed since the whole reason it's stored in memory right now
is for speed of lookups.

I looked around at what databases the Clojure community is using, and of course the most popular is
[datomic](https://www.datomic.com), but that is both proprietary as well as cloud-focused. There are
open source [datalog](https://en.wikipedia.org/wiki/Datalog) databases as well though, I've started
playing around with [datahike](https://github.com/replikativ/datahike) and
[datalevin](https://github.com/juji-io/datalevin) (noticing a pattern with these names?). So far
I've had the most luck with datalevin in my
[experiments](https://github.com/skynet-gh/skylobby/compare/datalevin-rapid-index) and might even
finish before publishing this blog post.
