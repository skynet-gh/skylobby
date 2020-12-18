{:title "Development Diary"
 :layout :page
 :page-index 1}

## Introduction

In June this year, Good Old Games had a promotion where they gave away the classic RTS game Total Annihilation for free. I never played the original game before, but I had played games on the open source engine inspired by it, [Spring](https://springrts.com/), back in the late 2000s ([proof](https://springrts.com/phpbb/viewtopic.php?f=11&t=13885)). So me and a friend fired it up to try it out on LAN.

Turns out it is pretty hard to get the networking going, which is expected for an old game. So we turned instead to what we knew, and downloaded the main way people have been running Spring games for over a decade, [SpringLobby](https://springlobby.springrts.com/).

While we were able to play some games, there were crashes, frequent random errors, and performance issues. So I decided it might be a fun side project to work on a replacement.

## Goal and Approach

Implement a desktop app replacement for SpringLobby that can connect to [uberserver](https://github.com/spring/uberserver) (the main Spring lobby server implementation), using the [Spring lobby protocol](https://springrts.com/dl/LobbyProtocol/ProtocolDescription.html), and which can run the Spring application using the interface of passing in the [`script.txt` file](https://github.com/spring/spring/blob/104.0/doc/StartScriptFormat.txt). The program should also at least point users in the direction of where to get resources such as maps and games to play.

I'm going to implement this using [Clojure](https://clojure.org/), a JVM Lisp that emphasizes data. There is [a great talk by the language designer](https://www.youtube.com/watch?v=2V1FtfBDsLU) on the types of programs the language is targeting, and I think this one fits directly into that category of "situated" programs.

It will need to have a responsive UI for user input, a TCP connection to the lobby server, as well as some method of tracking resources on disk that will change over time, plus update lists of new resources that can be acquired, not to mention actually starting and monitoring the engine process itself.

Luckily there are language features, as well as libraries in Clojure or Java that provide the building blocks for all of this.

## First Steps

I started with a basic UI using a library I've been keeping an eye on, [cljfx](https://github.com/cljfx/cljfx), which turns JavaFX into something resembling a React application, where you describe how state is rendered into the UI. Any changes you make to the state are watched by the renderer, which adjusts the UI accordingly. This is all done based on a [Clojure Atom](https://clojure.org/reference/atoms). You can see it [started off small](https://github.com/skynet-gh/alt-spring-lobby/blob/732453b5665d4a04c1c7c9f6c26dd7a2ea61c490/src/clj/spring_lobby.clj#L13).

[Screenshot of Initial UI](/img/initial-ui.png)

Around the same time, I started talking to a local uberserver instance. I needed to make some changes to get it working on Ubuntu 20.04 and add enable debug logging, so I [forked the repo and made changes here](https://github.com/skynet-gh/uberserver/tree/fix-mysql).

Once I had the server running, I added the [Aleph](https://github.com/clj-commons/aleph) networking library, which provides an easy wrapper over Netty for sending and receiving messages on a TCP connection. I also used [gloss](https://github.com/ztellman/gloss) both for splitting the TCP data into messages, as well as [parsing some of the encoded pieces of the messages](https://github.com/skynet-gh/alt-spring-lobby/commit/b91457792627504cc7e9f31a4d41625b373f2eb4#diff-8d4537ca92172eee422e0f23226d7478af9b95bed4cfc35c566b99a84af89109R33).

## Running Spring

After that, I took a break from things for a month, and when I returned I added the [7-Zip-JBinding](http://sevenzipjbind.sourceforge.net/) library for parsing most maps, and extracting engine archives. I also added [basic exec of the Spring executable](https://github.com/skynet-gh/alt-spring-lobby/commit/8c17ad911ba2cd1b3658b416a186570011c8b95f#diff-851cffb722e6a3673b05253c604a4dc51080128152956c9c0fef2d5f09e6d713R301), and some more parts of the protocol, like adding bots.


[Screenshot of UI With Battle and Bots](battle-and-bots.png)


## Parsing Maps

Parsing map files involves multiple archive formats (Zip and 7-Zip), a [custom binary format](https://springrts.com/wiki/Mapdev:SMF_format), a [custom config text format](https://springrts.com/wiki/Mapdev:SMD) for older maps, as well as [executing Lua](https://springrts.com/wiki/Mapdev:mapinfo.lua) for the *new* format.

I started with the old SMD format, which also seems to be the same format that the `script.txt` is written in. There's a library for creating grammars for parsing called [instaparse](https://github.com/engelberg/instaparse) that often makes describing custom formats like these fairly straightforward. In this case the grammar [came out to roughly ten lines](https://github.com/skynet-gh/alt-spring-lobby/blob/cc337af57d8b5ad88599d36b3e0c0bf739299557/src/clj/spring_lobby/spring/script.clj#L66-L75) plus a number of lines to postprocess it into a format that's easier to work with.

However, there is a possible case with instaparse where [parsing never terminates](https://github.com/Engelberg/instaparse/issues/196). So I wrap its execution with a library called [clojail](https://github.com/flatland/clojail), which basically runs a Thread, and calls `.stop` if it doesn't complete in time.


For the new `mapinfo.lua` format though, it's more difficult, since the map data is now stored as Lua code. I'm sure this is fine in the engine itself when the Lua is being executed, but it's a pain to deal with outside of that context. Thankfully, there's a Java library for executing Lua code, [luaj](https://github.com/luaj/luaj). This, along with some [mocking of the Spring internals](https://github.com/skynet-gh/alt-spring-lobby/commit/838c01ecbdab69d81dac133ce4f558d0ad0908ba#diff-d599e6e8d4aaec917ba9ab99739b5eb3ef9ea9ca83e9208e16fdfe92e7754002R19-R32) that sometimes leak into map "data", allows parsing across all maps I've tested.

Now for the most complex piece of all, the actual map data file `.smf`. This is a binary file with a header describing the layout. Fortunately, there's a great library [`smee/binary`](https://github.com/smee/binary) that fully supports formats like this with headers. There is one more source of complexity here, since the map files can be laid out in any order, so we need to use the offsets and lengths for each section. Once the file is parsed into its component pieces though, we are only partly done: we still need to parse the minimap image.

The minimap image is stored as the pixels of a DXT1 compressed image, one of the algorithms for [DDS](https://docs.microsoft.com/en-us/windows/win32/direct3ddds/dds-header). As far as I can tell, Java support for this format is... almost nonexistant. Not to mention, the map format doesn't store the whole file, but just the pixes, which happen to be a magical 699048 in length.

After some [failed attempts](/img/failed-dworld-minimap.png), I managed to grab the bytes needed to extract the `.dds` image. The last step is to convert from DDS to a Java Image, either through a library or by manually creating from raw pixels.

I looked at a number of other implementations of this, like [BALobby](https://github.com/AledLLEvans/BALobby/blob/7b018ba3e25d95e88b19e5c42446038a6adec5de/spring.lua#L8-L36) and [smf_tools](https://github.com/enetheru/smf_tools/blob/master/src/smf.h#L43-L66). `smf_tools` uses the Squish library to uncompress the minimap data into pixels. So I stumbled across [JSquish](https://github.com/acmi/jsqush), a small Java implementation of the Squish compression. While it doesn't seem to be in Nexus or other package management, a jar of it exists [here](https://github.com/Dahie/DDS-Utils/tree/master/DDSUtils/lib). Now I can fully extract the minimap bytes into an Image, for diplay or saving to disk in a more standard format like `.png`.

Not sure there's a better way, perhaps maybe just using JNI to call some C++ or Lua implementation. But that might just lead to more reliance on these obscure formats in the first place.


## Resources

Now we have some basic processing of local resources, but it would be useful to be able to get new resources like games to play. One method of doing so is also custom to Spring, called [Rapid](https://springrts.com/wiki/Rapid). Rapid seems like a way to deal with small changes between game versions without rolling a new archive, which can be quite overall hundreds of megabytes or larger. In other words, similar to git in many ways. The `.sdp` format is binary and can be parsed with the same binary library mentioned earlier. To actually download packages, I just shell out to the [`pr-downloader` executable](https://github.com/spring/pr-downloader).


Other resources can be downloaded with http, if you know where to look. I use the main [`clj-http`](https://github.com/dakrone/clj-http) library which wraps Apache HttpComponents, and this allows easy download to a file, and progress monitoring. At first I tried to build urls based on specific content to look for, but there are issues such as not knowing what case the file will have, or if it is `.sdz` or `.sd7`. Now it periodically fetches and parses [a few known websites](https://github.com/skynet-gh/alt-spring-lobby/blob/5ea022f03380ab9f6065e5d38f0442277f918e43/src/clj/spring_lobby/http.clj#L22-L32) that are usually html or xml.

## Development Workflow

A brief intermission to talk about development workflow. The goal is to have as tight a feedback loop as possible, while recovering from errors in order to not restart the repl for often days at a time. I mainly rolled my own here to learn a bit and get more control. There is tons of potential from just using Clojure's [vars](https://clojure.org/reference/vars) and [refs](https://clojure.org/reference/refs). I've previously used others like [component](https://github.com/stuartsierra/component) and [system](https://github.com/danielsz/system), and have heard of others like [mount](https://github.com/tolitius/mount) but haven't used it.

The main reloading library is [tools.namespace](https://github.com/clojure/tools.namespace) which is used for most reloading as far as I can tell. Basically, when `refresh` is called, all old namespaces (usually one file maps to one namespace) are unloaded, then the code is recompiled from what's now in the files on disk which should recreate the namespaces, and an optional `:after` function is called. I use a file watching library called [hawk](https://github.com/wkf/hawk) to do the file watching and some teardown (like stoping periodic tasks started with [chime](https://github.com/jarohen/chime/) as well as restoring the program state.

Basically, the program uses one [`atom`](https://clojure.org/reference/atoms) for its state, and various threads makes changes to it. Another feature that I didn't know about until recently is [`add-watch`](https://clojure.github.io/clojure/clojure.core-api.html#clojure.core/add-watch) which calls a function whenever the state of a ref like an atom changes. I (ab)use this for things like updating config files when the user makes changes in the UI, or loading the minimap from disk when the current map changes.

There are a few situations where reloading would not update the running program, that needed special treatment. The UI view, the UI event handler, and the TCP client handler. For these, in dev mode, I use `(var-get (find-var ...)` to dynamically get the new version when namespaces are refreshed. That way, the program will keep its entire state, keep rendering the UI, talking to the server, etc., even after recompile, which is usually less than a second. This works fairly well, although compile errors sometimes cause namespaces to not be reloaded, and various things will break until full compilation happens again.


## File Scanning

My first attempt to updating resources over time was to use file watchers with something like [hawk](https://github.com/wkf/hawk) which I use in the development loop.


![Present Day Screenshot](/img/battle-minimap-and-resources.png)


## Advancements

Now that we have feature parity with SpringLobby in a number of areas, I've started finally adding the improvements I set out to do in the first place. One annoyance, the "Random" starting positions didn't seem to be actually random, but rather just a hash or something. So I added [shuffling of team ids in this case](https://github.com/skynet-gh/alt-spring-lobby/commit/4eadacd84011c26918b905931344717790d86cdd), so my friends and I can start in different unknown locations.

Another issue, depending on where the uberserver is located, the wrong IP address for the host may be used. So I [added an override](https://github.com/skynet-gh/alt-spring-lobby/commit/ffe8eec18c3fadc2ffc9612a2aca0808da7c379b), since it is just a part of `script.txt` ultimately, although the lobby protocol uses it in battle open.

One thing I want to do soon, is add a key customization UI, and some alternative configurations, so that my Starcraft friends can play the game.

## Future

Since it's part of the lobby protocol, I do want to implement chatting sometime, despite the prevalence of discord.

I need to add multiple servers and logins support, soon.

Need to parse the `.smf` file more properly. Don't try to use `ordered-map` to parse the body, just use offsets and `ByteBuffer.wrap`. Then we can get the heightmap too.


I try to capture these in CHANGELOG.md

## This Website

To make these web pages, I used, you guessed it, another Clojure library called [Cryogen](http://cryogenweb.org/index.html). After a few false starts I figured out the URI paths and how to export to the [`gh-pages` branch](https://github.com/skynet-gh/alt-spring-lobby/tree/gh-pages) so it will be hosted here.
