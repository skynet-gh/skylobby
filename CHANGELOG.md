# Changelog

Notable changes and todo list.

## TODO - future

Future Features:

- Query auto hosts for battle details
- Visually group teams in players table
- Arbitrary engine directories
- Open battle links
- First run intro window
- Highlight window when rang or new direct message
- Add new butler downloader system
- Chat URL highlighting and clicking
- Zero-K server protocol
- Merged view of multiple servers
- Auto switch spring settings per server or per game
- Improve map filter window
- Call Spring unitsync to get resource hashes


## Actual changelog

### [0.7.24]

- Fix engine sync not showing source update
- Add discord promote button for metal factions
- Fix mod git versioning

### [0.7.23]

- Add linux jar without version in filename
- Internal build changes

### [0.7.22]

- Add very rudimentary support for BAR Chobby scenarios
- Fix joining passworded battles
- Fix engines list in host battle window
- Add button to close replay details
- Add discord promote button for techa hosts

### [0.7.21]

- Add password reset button
- Fix replay downloads from main table
- Add support for replays in .sdf format

### [0.7.20]

- Fix parsed replays not being saved to file
- Add setting to color your username in chat
- Make auto rejoin battle default
- Add some spacing between buttons for battle
- Add list maps button when host is a bot
- Add setting to refresh replays after game
- Fix cancelling vote message regex
- Improve engine choice download
- Fix host battle window close

### [0.7.19]

- Make it easier to download different engine arch
- Add handler for FRIENDREQUEST
- Set default engine download source as springrts
- Fix battle channel name to fix join leave
- Fix priorities for maps refresh

### [0.7.18]

- Add options for map and AI
- Fix ignore not respected for tab highlighting
- Add setting to ring on auto unspec
- Fix prioritizing battle games, add rapid priority

### [0.7.17]

- Fix singleplayer when no username is set
- Fix some issues with auto unspec and ready
- Add report user for teiserver
- Add cli option to set port for web ui

### [0.7.16]

- Fix default spring root for web ui
- Add parameter to web ui js to attempt to force cache miss
- Upload .msi again without version for direct linking

### [0.7.15]

- Add engine overrides to config
- Improve sync speed after downloading or force check
- Add changing usernames and passwords for servers in web ui

### [0.7.14]

- Add engine select when multiple for a version
- Fix state churn issue from tasks

### [0.7.13]

- Add launcher for web ui
- More work on web ui
- Update .desktop file with better keywords
- Add cljs compile to release action

### [0.7.12]

- Fix issues with singleplayer battle
- Add setting to ring after game ends
- Add basic search to settings
- Add game refresh priority for downloaded sdp
- Fix game refresh task being added multiple times
- Remove downloads from invalid sources

### [0.7.11]

- Add support for friends list
- Add minimap size to persistent config
- Stop rendering chat and console when server tab not selected

### [0.7.10]

- Rework auto resources for rapid
- Delete sdp files if they are invalid
- Update BAR server url

### [0.7.9]

- Fix engine download source button
- Add engine source update to auto resources

### [0.7.8]

- Split rapid data by spring root to prevent conflicts
- Add spring root for refresh tasks to improve speed
- Add indication when resources are refreshing
- Add icons showing resource status for battles

### [0.7.7]

- Fix color picker
- Attempt to fix issues with auto get resources
- Start work on lightweight web UI

### [0.7.6]

- Fix Add AI button disabled when it shouldn't be
- Add button to disable spads map rotate
- Make auto launch enabled by default
- Fix auto unspec not using desired ready state

### [0.7.5]

- Fix an issue when windows are minimzed then reopened

### [0.7.4]

- Move singleplayer battle to a tab

### [0.7.3]

- Move add bot buttons to popup window
- Add back bot name input
- Actually fix clicking old battle tabs
- Standardize font icon sizes in battle buttons

### [0.7.2]

- Fix issue with download source updates
- Fix issue with http download window
- Fix issue with clicking old battle tabs
- Improve visual feedback when updating downloads

### [0.7.1]

- Add auto complete to chat input
- Add setting to show battles you have left
- Hide SPRINGIE messages in ZK replay chat log
- Persist auto launch per server

### [0.7.0]

- Get auto update working on Windows

### [0.6.20]

- Fix chat and console auto scroll
- Add layout orientation picker for battles/users
- Switch large config files to nippy for performance

### [0.6.19]

- Refresh mods after rapid package update
- Add force sync check label and button
- Fix replay file association when replay has spaces
- Fix standalone replays window
- Add handler for tei battle rename

### [0.6.18]

- Add max tries to springfiles auto download
- Improve feedback from springfiles downloading
- Spread out auto connect and do not focus server tabs
- Move replays download to separate window

### [0.6.17]

- Attempt to fix issue with auto get resources and rapid
- Fix issue setting custom spring root
- Fix spring root for singleplayer
- Add controls to hide battles by status
- Add missing vote cancel spads message format
- Add sort order to servers

### [0.6.16]

- Fix chat tab highlight when not focused
- Stop showing battle time when started before login
- Prevent attempt to watch multiple replays
- Track spring running per battle to prevent dupe starts
- Add info message in chat when promoting to discord
- Make auto connect servers on init async
- Prevent attempt to join invalid channels

### [0.6.15]

- Add info messages in chat for slash commands
- Add /ignore and /unignore slash commands
- Add promote to discord for BA battles
- Fix tooltips stealing window focus
- Fix resource issues with EvoRTS
- Improve sorting of battle players table
- Add x button to clear map boxes
- Add flags for players on battle hover
- Ignore now works on relayed ingame messages too
- Add threshold for minimum map box sizes
- Fix local spectate change not immediately set in state
- Add spads message for vote cancel game launch

### [0.6.14]

- Rework replays to save memory and startup time
- Replays will be migrated to new format on first Refresh
- Increase frequency of battle sync watchers
- Add join/leave messages for dm channels
- Display download progress during rapid update
- Improve performance of some client message handlers
- Change music to line up better with main window show

### [0.6.13]

- Add launchers for different max memory sizes
- Spread out initial task runs to reduce CPU on startup
- Fix battle sync check running when not needed
- Move 7z init later to improve startup time
- Remove dynamic require of ui ns to improve startup time
- Remove -server and -Xms jvm flags to attempt to improve startup time
- Remove large icons to reduce per-window memory footprint

### [0.6.12]

- Add mute for rings to the right of battle chat input
- Track user away time and display as tooltip
- Add button to cancel a queued task
- Add button to stop a running task, best to only be used if task is hung

### [0.6.11]

- Fix NPE in chat and console tabs

### [0.6.10]

- Fix singleplayer game not starting when spec
- Fix minimap image not showing after download

### [0.6.9]

- Fix server tab highlight on muted battle

### [0.6.8]

- Move battle votes below map in vertical layout
- Add highlighting for battle chat
- Add settings to control both battle and chat highlighting
- Add muting of individual chats for highlighting

### [0.6.7]

- Fix chat focus tracking

### [0.6.6]

- Make selected tabs per server
- Fix ba and spring official server names
- Make black css theme the default
- Fix minimap teams not using inc ids setting
- Fix chat and console reuse when server tab positions change

### [0.6.5]

- Add tab highlighting on new chats

### [0.6.4]

- Add tab highlighting on new chats
- Fix message context menu not working
- Fix minimap display for non-boxes and in replays
- Improve replay details parsing and caching
- Move all map images to files, improving performance and reducing cache size

### [0.6.3]

- Add cooldown to rapid auto download tries
- Fix rapid download engine file choice
- Fix ba github download source
- Reduce ui context cache size

### [0.6.2]

- Fix ready change in battle
- Fix singleplayer battle
- Fix some issues with auto get resources and sync status update
- Increase max heap size to 2GB

### [0.6.1]

- Add setting for focusing chat on new message
- Fix auto resources sometimes not running
- Add setting to join battles as a player
- Add cache for ui context to prevent OOM issue
- Lower cache sizes for maps, mods, replays
- Increase max heap size to 1.5G

### [0.6.0]

- Switch UI to use context for better performance

### [0.5.9]

- Fix issue with config validation

### [0.5.8]

- Fix error in starting spring when no old infolog exists
- Fix event objects sometimes ending up in config
- Fix issues with constraining windows to screen
- Add fade out/in for music on game start/end
- Add setting to prevent non-host rings
- Add setting to hide joinas spec messages
- Turn off auto unspec when leaving battle
- Fix all commands being hidden, not just votes
- Fix chat messages being logged in reverse order
- Fix colors in log file
- Prevent credentials from getting logged
- Attempt to parse new configs before writing, backup good configs

### [0.5.7]

- Add setting to hide vote chat messages
- Fix some host message and vote display issues
- Fix windows sometimes getting zero size
- Fix multiple auto download from springfiles
- Move user ignore to bottom of context menu
- Add setting to start numbering player and team ids at 1
- Fix auto get resources setting and add to battle resources panel

### [0.5.6]

- Add parsing of battle host messages
- Add battle votes panel
- Add setting to hide battle host messages by type
- Add port setting for battle hosting
- Make ring specs a task and disable button when running
- Improve rapid, download, and import windows
- Fix a number of reflection issues (performance)

### [0.5.5]

- Add support for STARTTLS
- Fix popout battle chat bleeding servers
- Add resource buttons below sync panes on battle
- Improve performance of replay filtering
- Fix another case where state watchers ran immediately
- Ensure all client-server messages are in console log
- Fix battle popout chat divider position npe
- Fix battle status sending empty color

### [0.5.4]

- Fix table sorting when items change
- Make players table resizing unconstrained
- Fix periodic state change watchers firing immediately
- Add setting to show team skill sums

### [0.5.3]

- Add setting to show battle below battles again
- Attempt to fix dropdown position issue
- Workaround for SSL login issue

### [0.5.2]

- Save window maximized states
- Reduce frequency of window state sync

### [0.5.1]

- Fix unable to stop auto unspec

### [0.5.0]

- Improve battle layout
- Add auto download maps from springfiles
- Make battle a tab in main window
- Add buttons to open windows for battles and chats
- Add refresh download source for engine button

### [0.4.18]

- Fix client id not set

### [0.4.17]

- Add support for client id other than zero
- Add backup of infologs
- Add promote button in battle
- Add support for user agent string override
- Fix text selection in chat and console on new content
- Fix issue with chat tab contents

### [0.4.16]

- Fix issues with chat auto scroll
- Fix issue with unready after game
- Fix unready on unspec and auto unspec
- Improve performance from window size and position saving
- Add saving of battle divider position
- Use random open port for singleplayer
- Decrease auto unspec cooldown
- Add BA github repo as games source
- Fix multiplayer buttons when a singleplayer battle is open

### [0.4.15]

- Improve chat auto scroll
- Improve performance of chat and console rendering

### [0.4.14]

- Add auto rejoin battle setting
- Add ring sound and volume settings
- Clean up battles buttons, host battle in its own window
- Save most window sizes and locations

### [0.4.13]

- Add autounspec when teamsize is changed

### [0.4.12]

- Add engine BAR105 special case
- Add setting for ready on unspec
- Fix ring specs happening on render thread
- Add count to replays view

### [0.4.11]

- Fix infinite loop in map and mod refresh when errors reading
- Fix auto unspec when a player leaves a battle

### [0.4.10]

- Fix infinite loop in map refresh with .sdd dirs
- Fix non-.sdd dirs in games being scanned
- Fix disable-tasks setting not being persisted

### [0.4.9]

- Add auto unspec checkbox
- Add highlight of username and custom words in chat
- Add logging of chat to files in `.skylobby/chat-logs`
- Fix unready after game using state when game started
- Add Ring Specs button
- Improve rendering performance of replays window
- General performance improvements
- Lower threshold for chat auto scroll
- Filter BarManager messages from chat

### [0.4.8]

- Add ignore user to context menu
- Add join and leave to chat

### [0.4.7]

- Update springfiles url to springfiles.springrts.com

### [0.4.6]

- Add download sources for Total Atomization Prime releases and maps

### [0.4.5]

- Fix unready after game setting
- Stop sending !joinas spec for most servers

### [0.4.4]

- Fix performance issue with chat and console
- Make unready after game a setting
- Fix battle layout and player colors settings not saved

### [0.4.3]

- Fix game specific settings backup and restore
- Fix replay chat spec player colors

### [0.4.2]

- Add option to auto backup/restore Spring settings by game type

### [0.4.1]

- Scroll chat and console to bottom on create
- Make auto scroll threshold based on component height
- Fix replay players table columns
- Add color to replay chat log

### [0.4.0]

- Select text in chat without mode change
- Auto scroll chat when at bottom, lock when scrolling up
- Add setting to hide player table columns
- Unready after game ends
- Add color to console

### [0.3.66]

- Fix git mod version sync
- Change here/away and play/spec to dropdowns
- Add up/down to fill previous chat messages
- Add CSS classes for some parts of chat
- Add setting to auto refresh replays

### [0.3.65]

- Add setting for git mod versioning

### [0.3.64]

- Fix register response message display
- Fix login error message for token auth

### [0.3.63]

- Add setting for chat font size
- Make start game sync match protocol sync status
- Re-enable matchmaking queue updates
- Fix token auth for SSL servers

### [0.3.62]

- Fix double click to open chat with user
- Fix auto scroll in popout chat window

### [0.3.61]

- Add battle chat window popout option
- Fix singleplayer battle
- Fix engine AIs
- Fix minimap scale issues
- Allow text input for spring root
- Disable matchmaking update until server is fixed

### [0.3.60]

- Add battle layout select for vertical or horizontal chat
- Add minimap size select
- Fix some render performance issues
- Fix matchmaking protocol
- Move matchmaking to a tab

### [0.3.59]

- Fix replay refresh for real

### [0.3.58]

- Fix replay refresh

### [0.3.57]

- Fix replay loading stuck on invalid files
- Fix map directories not being reloaded
- Fix duplicate maps not being removed

### [0.3.56]

- Display multiple download sources for maps
- Separate bot and human user counts

### [0.3.55]

- Fix agreement display and confirm

### [0.3.54]

- Update BAR server url
- Add BAR SSL server

### [0.3.53]

- Save and set preferred faction by game type
- Default ally to 0
- Add BAR GitHub maps download source
- Fix CSS for matchmaking window
- Add mod version and name without version to index
- Fix `CHANNEL` handler when no trailing whitespace
- Fix `CLIENTS` handler when no clients

### [0.3.52]

- Add download source for EvoRTS TAP GitHub release artifacts
- Add handler for `JOINBATTLEREQUEST`
- Add mod dependency for EvoRTS on its music mod
- Lighten shadow around player names
- Increase font size in chat
- Add resource buttons to singleplayer battle

### [0.3.51]

- Add shadow around player names
- Add option to interleave player ids in balance

### [0.3.50]

- Revert per-user install for Windows
- Add jvm flags for dpi scaling on Windows
- Fix factions for S44

### [0.3.49]

- Release to test auto updates

### [0.3.48]

- Fix mod update loop caused by re-scanning directories
- Fix auto update for installed exe

### [0.3.47]

- Set per-user install for Windows

### [0.3.46]

- Release to test auto updates

### [0.3.45]

- Add button to auto download updates and restart

### [0.3.44]

- Fix music auto play
- Add battle players color names by their personal color
- Fix singleplayer balance and fix colors
- Add colors from SPADS for different setups

### [0.3.43]

- Add battle run time display
- Add pause and resume of lobby music when in game
- Filter out non-playable files from music folder
- Fix `--music-volume` cli option and improve cli error messages

### [0.3.42]

- Add music player, `--music-dir`, and `--music-volume` cli options
- Fix clear start boxes
- Contain start boxes dragging to minimap
- Fix AI id in replay players table
- Change slowest state watchers to periodic

### [0.3.41]

- Add setting to disable tasks while in a game, for performance
- Fix battle hosting
- Add filtering for hosting replay files
- Add replay filtering by game id
- Improve replay view for online replays
- Switch auto resources to periodic for performance

### [0.3.40]

- Fix ingame status not set correctly
- Fix replay parsing sometimes causing memory issues

### [0.3.39]

- Fix auto launch detecting spectator incorrectly
- Stop sending `!joinas spec` when no script password
- Fix resource details caches not sorted

### [0.3.38]

- Add chat log to replay details view

### [0.3.37]

- Make auto launch only apply while spectating
- Add replay buttons to open folder or BAR online
- Fix rare issue with players table in replays

### [0.3.36]

- Add tag string field for replays
- Add dedupe of replays by id
- Fix filter by player name for online replays
- Add `--no-update-check` cli flag for embedded skylobby

### [0.3.35]

- Enable/disable chat auto scroll when at bottom/scrolled up
- Attempt to fix issue with chat moving on new message when scrolled up

### [0.3.34]

- Fix resource churn for replays and battle mods

### [0.3.33]

- Add rapid update to auto resource tasks
- Add uncaught error logging handler
- Fix modoptions in singleplayer
- Make rapid package update message more clear

### [0.3.32]

- Fix modoptions missing
- Add map description

### [0.3.31]

- Fix occasional crash due to profiler concurrency
- Fix battle map suggest

### [0.3.30]

- Fix ready checkbox not doing anything
- Fix selected replay when opening from file
- Fix bot version display when changing bot name
- Add button to connect to auto-connect servers

### [0.3.29]

- Add resource sync to left of map in replay view
- Fix typo causing map download progress to be hidden
- Fix replay sync springfiles download
- Fix selected replay watching

### [0.3.28]

- Fix replay watching
- Add `--css-file FILE` CLI option to set custom CSS using a file
- Add `--css-preset PRESET` CLI option to set a CSS preset theme
- Add `--replay-source DIR` CLI option to replace default replay sources with a custom list
- Add `--window-maximized` CLI option to start the main window maximized

### [0.3.27]

- Add /rename chat command
- Add handling for legacy battle chat
- Set ready and auto launch when playing
- Fix background color css issue
- Fix some windows not limited to screen size
- Fix handler for ADDUSER in some cases

### [0.3.26]

- Add file association for .sdfz
- Add launcher for skyreplays
- Fix display of modoption sections
- Decouple battle ready from auto start
- Add IPC server to open replay in running process
- Add support for css custom style

### [0.3.25]

- Add support for bridged chat
- Add custom CSS section to settings

### [0.3.24]

- Fix balance button and minimap type combo box
- Fix battles and users filter cli args
- Add springfiles download for maps

### [0.3.23]

- Improve installer user feedback
- Add battles and users filters with cli args
- Add spectate/play toggle button
- Fix broken add bot
- Add away mode button
- Fix minimap image caching
- Add new task type for downloads
- Fix team color orders
- Fix replays mod details
- Fix broken engine download
- Force tables to layout when they get items

### [0.3.22]

- Add server auto connect option
- Add `--chat-channel` cli arg

### [0.3.21]

- Fix `--skylobby-root` not coerced to file

### [0.3.20]

- Add flag icons for countries
- Add `--skylobby-root` to set skylobby config and log directory
- Fix auto download which broke after multi spring dirs

### [0.3.19]

- Greatly improve performance by making some state watchers periodic
- Fix resource details cache empty key issue for map and mod sync

### [0.3.18]

- Fix map details not loading automatically

### [0.3.17]

- Fix use of deprecated api removed in Java 16
- Add max tries and retry button for battle map details
- Fix username and password with `--server-url` arg

### [0.3.16]

- Add status icon for player sync
- Add chat commands for /msg and /ingame
- Add CLI flag `--server-url` to set selected server

### [0.3.15]

- Add CLI flag `--spring-root`
- Send actual sync status to server
- Fix concurrency issue writing configs
- Re-enable Windows jar upload

### [0.3.14]

- Fix issue with map and mod details when changing spring dir
- Use file pickers for dir settings
- Show spring directories on main screen
- Fix team number buttons
- Fix map filtering in singleplayer battle
- Improve layout of welcome page when in singleplayer battle
- Fix singleplayer battle engine/mod/map pickers set global choice

### [0.3.13]

- Improve feedback for when downloads and imports start
- Fix replays rapid download and git version action
- Fix rapid download window
- Fix SpringFiles download button appearing before doing a search
- Add more unit tests

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
