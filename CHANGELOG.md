# Changelog

Notable changes and todo list.

## TODO - future

Annoyances:

- SpringFiles download button appears before doing a search
- Tables are not laid out when they get items
- Chat box does not auto scroll, moves when new messages come in
- Ally/team numbers start at 0 instead of 1, different from SPADS
- Path settings are text fields not file pickers
- Rapid is not updated automatically
- Engine is not extracted automatically

Features:

- Up arrow in chat and console box should fill in last message
- Chat URL highlighting, text selection
- Zero-K server protocol
- Merged view of multiple servers
- Auto switch spring settings per server or per game
- Call Spring unitsync to get resource hashes

Server or SPADS changes:

- Hosted replay watching


## Actual changelog

### [0.3.12]

- Fix resources not being reloaded in all Spring directories
- Add balance and fixcolors buttons
- Fix an issue starting a game from git

### [0.3.11]

- Fix dependency issue with async library

### [0.3.10]

- Fix jar main class
- Fix battle map and mod details retry

### [0.3.9]

- Fix chat in pop out battle window
- Add support for teiserver token auth
- Fix matchmaking after multi server
- Fix battle map and mod sync after multi server

### [0.3.8]

- Fix circular dependency issue in installer
- Fix minimap size when loading
- Add ability to retry rapid download in battle view

### [0.3.7]

- Add support for TLS communication to the server

### [0.3.6]

- Add config in server window to set set Spring directory
- Add ability to login to same server as multiple users
- Add slider to shrink battles table when in a battle

### [0.3.5]

- Fix task workers not starting
- Fix resource details update spawning a thread
- Improve table column sizing

### [0.3.4]

- Rework task system to improve responsiveness
- Split mod refresh task into chunks
- Fix a parsing operation in render loop
- Fix chat mode change

### [0.3.3]

- Fix battle view render error
- Fix start positions in singleplayer

### [0.3.2]

- Add basic support for spring settings backup and restore
- Fix console commands
- Fix chat and channels
- Fix app close

### [0.3.1]

- Fix console not showing
- Add Hakora site as source for maps
- Stop building skyreplays release artifacts

### [0.3.0]

- Add ability to connect to multiple servers simultaneously
- Add local battle separate from multiplayer
- Improve map and game caching
- Add .sdd map handling
- Add test coverage

### [0.2.15]

- Add filtering by replay source

### [0.2.14]

- Improve feedback for downloading replays
- Add recursive replay sources

### [0.2.13]

- Greatly improved performance
- Add download of BAR replays
- Add workaround for BAR sidedata change
- Download resources from springfiles by search
- Update matchmaking to new protocol.

### [0.2.12]

- Add color mode to chat channels
- Improve coloring in battles table, bold players
- Add skylobby update check

### [0.2.11]

- Make auto download battle resources default
- Fix skill uncertainty in replays view
- Fix triple click on battle joining then leaving
- Add display of player counts in battle (e.g. 2v2)
- Add support for teiserver matchmaking

### [0.2.10]

- Improve main window layout at small sizes
- Improve battle resource sync panes and auto download
- Improve replay map and mod detail updating
- Add license file

### [0.2.9]

- Add singleplayer battle
- Add option to auto download battle resources
- Improve performance when changing spring isolation dir
- Improve startup performance by delaying jobs
- Cache rapid file scan based on last modified
- Tweak jvm args for performance

### [0.2.8]

- Add back Linux jar building

### [0.2.7]

- Fix spring isolation dir config ignored in some places
- Fix engine executables not being set to executable (Linux)
- Fix pr-downloader location in some older engines
- Fix register confirm agreement not using verification code
- Fix chat channels sharing draft message
- Add check box to disable chat and console auto scroll
- Add color for some status icons
- Add map size, metalmap, and heightmap to replays window
- Stop uploading build jars to release, installers only

### [0.2.6]

- Initial public release

### [0.2.4 and 0.2.5]

- Fix Windows packaging

### [0.2.3]

- Simplify and reorder battles and players tables
- Fix BAR location on Linux
- Fix hitching due to task workers doing unneeded writes

### [0.2.2]

- Add ring handler and sound effect

### [0.2.1]

- Add setting to change Spring directory

### [0.2.0]

- Add standalone replays packages
- Add !cv start when playing but not host
- Sort battle players list same as replays
- Fix chat messages
- Fix script password in JOINBATTLE
- Fix game launching when not ready or resources missing
- Fix battle map change detection
- Fix windows changing order when opened/closed
- Fix replays window skill including spectators

### [0.1.7]

- Add script password handling
- Improve installer with version so upgrade works
- Add app icon
- Save login per server
- Add settings window with custom import and replay paths
- Fix tab switch on new chat
- Fix springfightclub compatibility by adding more comp flags
- Fix some issues with battle resources not refreshing
- Change windows installer to .msi

### [0.1.6]

- Fix npe on Linux due to JavaFX TabPane selection model differences

### [0.1.5]

- Add direct messaging
- Add console tab to send raw commands
- Fix game not starting when host starts
- Add skill filtering to replays window
- Fix some table sorting issues

### [0.1.4]

- Rename to skylobby
- Fix sidedata hardcoded for BA and BAR (now supports Metal Factions)
- Fix windows being larger than screen
- Improve replays viewer: remove invalid, fix watch status, filter terms with whitespace

### [0.1.3]

- Store replay details in a file and track watched status
- Filter replays by game type and number of players
- Add progress indicators for replay resource buttons
- Improve performance for downloads and imports

### [0.1.2]

- Replay watcher
- Starting position rectangles for choose in game
- Chat
- Store multiple servers and login per server

### [0.1.1]

- Add ability to register on server

### [0.1.0] - 2020-12-18 to 2020-12-27

Initial public release.
- GitHub Actions to publish releases and build jars and installers
- GitHub Pages for dev blog and user guide
- GitHub Actions for unit tests
- Switch map load to fast 7zip
- Map detail browser with minimap images
- Download BAR engine from GitHub

### [pre-0.1.0 part 3] - 2020-11-15 to 2020-12-17

Fairly usable now.
- Manage resources (engines, mods/games, maps).
- Add many paths to get resources: varous http sites, rapid
- Parse modinfo
- Parse minimap and metal map directly from files
- Start games from app directory without resource copying
- Fully flesh out battles
  - Add minimap, start positions
  - Support all battle status changes in protocol

### [pre-0.1.0 part 2] - 2020-09-06 to 2020-09-07

Start basic games.
- Run Spring in isolation mode
- Fix hard coding username, password
- Add bots to battles
- Add basic battle status

### [pre-0.1.0 part 1] - 2020-07-14 to 2020-07-26

Basic UI and client.
- Initial UI
- Basic communication with Uberserver
  - Login, battles
- Ability to run spring with script.txt
