# Changelog

## [0.8.8](https://github.com/skynet-gh/skylobby/releases/tag/0.8.8) - 2022-03-25

- Use dbus-send for file open on Linux

## [0.8.7](https://github.com/skynet-gh/skylobby/releases/tag/0.8.7) - 2022-03-23

- Fix bot state changes in direct connect battle

## [0.8.6](https://github.com/skynet-gh/skylobby/releases/tag/0.8.6) - 2022-03-21

- Fix file browse on Linux
- Fix away dropdown in direct connect battle

## [0.8.5](https://github.com/skynet-gh/skylobby/releases/tag/0.8.5) - 2022-03-20

- Add bots to direct connect battles
- Add modoptions to direct connect battles
- Make Add AI button show up when spec
- Fix start game button for direct connect host when spec

## [0.8.4](https://github.com/skynet-gh/skylobby/releases/tag/0.8.4) - 2022-03-20

- Fix auto get resources
- Add handler to prevent rejoin battle on flood protect
- Fix direct connect client chat
- Add direct connect chat commands

## [0.8.3](https://github.com/skynet-gh/skylobby/releases/tag/0.8.3) - 2022-03-19

- Add setting to auto get replay resources
- Fix singleplayer Start Game button text

## [0.8.2](https://github.com/skynet-gh/skylobby/releases/tag/0.8.2) - 2022-03-18

- Use spring roots as replay sources
- Fix replays filter not matching some exact names
- Add search for checkbox settings by name

## [0.8.1](https://github.com/skynet-gh/skylobby/releases/tag/0.8.1) - 2022-03-17

- Fix multiple login errors overriding each other
- Add direct connect start boxes and start points minimap dragging
- Improve layout of direct connect by splitting into Host and Join tabs

## [0.8.0](https://github.com/skynet-gh/skylobby/releases/tag/0.8.0) - 2022-02-28

- Add LAN / direct hosting
- Fix battle panel display when not as tab

## [0.7.84](https://github.com/skynet-gh/skylobby/releases/tag/0.7.84) - 2022-02-25

- Add RPM package build

## [0.7.83](https://github.com/skynet-gh/skylobby/releases/tag/0.7.83) - 2022-02-25

- Add server setting for character encoding
- Spread out commands on login to avoid overwhelming server
- Ensure error message shown when non-user-requested disconnect happens
- Minor web UI improvements
- Fix native image build

## [0.7.82](https://github.com/skynet-gh/skylobby/releases/tag/0.7.82) - 2022-02-21

- Disable utf-8 for now because some servers send invalid data
- Increase cache size for UI

## [0.7.81](https://github.com/skynet-gh/skylobby/releases/tag/0.7.81) - 2022-02-20

- Add delete of SDP file on rapid error

## [0.7.80](https://github.com/skynet-gh/skylobby/releases/tag/0.7.80) - 2022-02-18

- Add setting to disable battle preview

## [0.7.79](https://github.com/skynet-gh/skylobby/releases/tag/0.7.79) - 2022-02-12

- Add fallback on Apache commons-compress for 7z maps
- Fix colors in rotated log files
- Fix battle preview players by team colors

## [0.7.78](https://github.com/skynet-gh/skylobby/releases/tag/0.7.78) - 2022-02-09

- Fix engine version re-checking
- Add output of raw spring stdout and stderr to `skylobby-spring.log` file
- Fix some issue with importing file to itself
- Add player colors to battle preview
- Add Report Bug button leading to GitHub issues

## [0.7.77](https://github.com/skynet-gh/skylobby/releases/tag/0.7.77) - 2022-02-09

- Fix engine version detection with multiple lines of output

## [0.7.76](https://github.com/skynet-gh/skylobby/releases/tag/0.7.76) - 2022-02-03

- Fix map and game refresh corrupt with files
- Fix SDD detection not case insensitive
- Fix game not launching when in sync and host start
- Fix filtering modoptions by changed only
- Add caching of current battle minimaps

## [0.7.75](https://github.com/skynet-gh/skylobby/releases/tag/0.7.75) - 2022-02-01

- Fix join battle issue
- Fix issue with battle preview
- Add more delete of corrupt rapid

## [0.7.74](https://github.com/skynet-gh/skylobby/releases/tag/0.7.74) - 2022-01-30

- Add UI for AccoladesBot
- Use modinfo to determine mod dependencies
- Add filtering for modoptions
- Add join queue status button

## [0.7.73](https://github.com/skynet-gh/skylobby/releases/tag/0.7.73) - 2022-01-28

- Add button for Teiserver join queue
- Fix performance issue with many open servers

## [0.7.72](https://github.com/skynet-gh/skylobby/releases/tag/0.7.72) - 2022-01-27

- Fix music fade when starting a game
- Fix issues with auto get resources
- Update music queue immediately when changing dir

## [0.7.71](https://github.com/skynet-gh/skylobby/releases/tag/0.7.71) - 2022-01-26

- Fix HostIP in start script when hosting
- Fix Fix Colors when hosting
- Fix login error messages
- Add new task category for Rapid so it can run in parallel to http downloads

## [0.7.70](https://github.com/skynet-gh/skylobby/releases/tag/0.7.70) - 2022-01-25

- Fix auto rejoin battle

## [0.7.69](https://github.com/skynet-gh/skylobby/releases/tag/0.7.69) - 2022-01-24

- Fix some issues with battles table and joining battles
- Add Leave Battle button to battles buttons
- Add sync status to players tooltip

## [0.7.68](https://github.com/skynet-gh/skylobby/releases/tag/0.7.68) - 2022-01-24

- Fix issues with auto get resources
- Fix select replay id key
- Auto extract engine if it is downloaded
- Fix rapid packages update cooldown when no engine
- Fix engine sync pane extract button

## [0.7.67](https://github.com/skynet-gh/skylobby/releases/tag/0.7.67) - 2022-01-23

- Add bot kick and options buttons in by team view
- Add bot owner and type to tooltip in table view
- Fix potential timing issue with joining and leaving chat channels

## [0.7.66](https://github.com/skynet-gh/skylobby/releases/tag/0.7.66) - 2022-01-22

- Prevent bot name from containing whitespace
- Fix modoption issue when changing servers

## [0.7.65](https://github.com/skynet-gh/skylobby/releases/tag/0.7.65) - 2022-01-22

- Add minimap start points for replays with box starts

## [0.7.64](https://github.com/skynet-gh/skylobby/releases/tag/0.7.64) - 2022-01-21

- Fix players table sending updates when it should be read-only
- Fix chat highlighting messages from automated chat commands
- Align skill and status in players by team view

## [0.7.63](https://github.com/skynet-gh/skylobby/releases/tag/0.7.63) - 2022-01-20

- Fix team balance buttons in singleplayer
- Fix scenarios layout on smaller resolutions
- Fix issue with joining battle after leaving
- Improve labels on welcome tab

## [0.7.62](https://github.com/skynet-gh/skylobby/releases/tag/0.7.62) - 2022-01-19

- Fix host battle
- Fix scenarios difficulty picker and restricted units

## [0.7.61](https://github.com/skynet-gh/skylobby/releases/tag/0.7.61) - 2022-01-18

- Fix issue with login

## [0.7.60](https://github.com/skynet-gh/skylobby/releases/tag/0.7.60) - 2022-01-18

- Fix issue with join battle after leave
- Fix issue with battles table items deselected
- Fix issue with resending messages with no response

## [0.7.59](https://github.com/skynet-gh/skylobby/releases/tag/0.7.59) - 2022-01-18

- Fix error with battle popout causing UI to be unresponsive
- Fix possible source of chat input cursor position reset
- Move join battle after LEFTBATTLE handler if in battle
- Prioritize minimaps for battles
- Track and display status while preparing to executing spring
- Add resend of messages that server has not responded to in time
- Reduce load from checking for resources

## [0.7.58](https://github.com/skynet-gh/skylobby/releases/tag/0.7.58) - 2022-01-17

- Add skill and status players grouped by team, wrap in scroll pane
- Bundle CSS and font icons for web view to prevent remote fetches

## [0.7.57](https://github.com/skynet-gh/skylobby/releases/tag/0.7.57) - 2022-01-17

- Add setting to show players grouped by team
- Fix issues with battle preview

## [0.7.56](https://github.com/skynet-gh/skylobby/releases/tag/0.7.56) - 2022-01-15

- Add default engine for scenarios, and ability to get latest game
- Fix scenarios table changing size
- Add indication on chat tab when there are friend requests
- Add button to close battle preview
- Fix skill uncertainty in battle preview

## [0.7.55](https://github.com/skynet-gh/skylobby/releases/tag/0.7.55) - 2022-01-14

- Fix Discord release notify

## [0.7.54](https://github.com/skynet-gh/skylobby/releases/tag/0.7.54) - 2022-01-14

- Move Discord notify to release publish

## [0.7.53](https://github.com/skynet-gh/skylobby/releases/tag/0.7.53) - 2022-01-14

- Move Discord notify to when release is created

## [0.7.52](https://github.com/skynet-gh/skylobby/releases/tag/0.7.52) - 2022-01-14

- Fix Discord notify release action

## [0.7.51](https://github.com/skynet-gh/skylobby/releases/tag/0.7.51) - 2022-01-14

- Add button to delete corrupt archives after Spring crash
- Filter replays by filename
- Fix resource refresh in replays
- Fix host disappearing when getting battle status (caused -1 player counts)
- Add GitHub action to create release from changelog
- Add GitHub action to notify Discord when release is published

## [0.7.50](https://github.com/skynet-gh/skylobby/releases/tag/0.7.50) - 2022-01-12

- Add parsing and display of more replay messages
- Fix issue with modoptions in singleplayer
- Fix issue with battle split pane position saving
- Add display of infolog when Spring crashes
- Add setting to show Spring command for debug

## [0.7.49](https://github.com/skynet-gh/skylobby/releases/tag/0.7.49) - 2022-01-11

- Fix file browsing for some resource files, or if path does not exist
- Add ability to filter console messages
- Fix off by one in battle status parse

## [0.7.48](https://github.com/skynet-gh/skylobby/releases/tag/0.7.48) - 2022-01-10

- Fix spring root picker showing when cli arg is set

## [0.7.47](https://github.com/skynet-gh/skylobby/releases/tag/0.7.47) - 2022-01-10

- Add setting to show hidden modoptions, default off

## [0.7.46](https://github.com/skynet-gh/skylobby/releases/tag/0.7.46) - 2022-01-09

- Fix battle status parsing for id and ally

## [0.7.45](https://github.com/skynet-gh/skylobby/releases/tag/0.7.45) - 2022-01-08

- Add timestamps to replay chat
- Fix git versioning singleplayer issues
- Fix battle status parsing
- Increase size of controls in singleplayer

## [0.7.44](https://github.com/skynet-gh/skylobby/releases/tag/0.7.44) - 2022-01-06

- Add task to delete `rapid` dir if a `versions.gz` is corrupt
- Improve git mod versioning, update `modinfo.lua` so replays work
- Add experimental setting to show battles table with map images
- Fix springfiles search download if not found
- Fix some incoming chat focus issues
- Show singleplayer game as running but still allow multiple copies if needed
- Fix game and map sync showing unknown for battle details preview
- Fix game dependencies sync
- Increase tooltip delays to reduce jarring popups

## [0.7.43](https://github.com/skynet-gh/skylobby/releases/tag/0.7.43) - 2022-01-06

- Fix some sent messages not recorded to correct server
- Clean up spectate change code

## [0.7.42](https://github.com/skynet-gh/skylobby/releases/tag/0.7.42) - 2022-01-05

- Fix bool modoptions in start script

## [0.7.41](https://github.com/skynet-gh/skylobby/releases/tag/0.7.41) - 2022-01-04

- Fix modoption for singleplayer
- Make springfiles search download if found
- Add basic battle preview on click in battles table
- Fix tab highlighting by adding to themes

## [0.7.40](https://github.com/skynet-gh/skylobby/releases/tag/0.7.40) - 2022-01-01

- Fix an issue with sending extra battle status changes
- Fix Windows file browse when spaces in path
- Add `--open-url` CLI arg to open a page in browser on start
- Add natType to host battle window
- Fix host battle not leaving current battle first
- Filter prereleases from update check

## [0.7.39](https://github.com/skynet-gh/skylobby/releases/tag/0.7.39) - 2021-12-30

- Fix leave messages in battle chat
- Add keyboard shortcuts to close tabs or quit
- Add server ping tracking and display
- Make selected tab more visible in black theme
- Add delete of old skylobby update jars on start
- Make tasks window a tab

## [0.7.38](https://github.com/skynet-gh/skylobby/releases/tag/0.7.38) - 2021-12-31

- Make main menu buttons the same size and add icons
- Improve performance when using windows as tabs
- Add css classes skylobby-chat and skylobby-chat-input
- Improve feedback when loading custom css

## [0.7.37](https://github.com/skynet-gh/skylobby/releases/tag/0.7.37) - 2021-12-30

- Disable auto unspec when specced for being unready
- Download files to temp folder then move into place
- Improve in-progress indication for resources
- Remove broken custom CSS button until solution found
- Fix native image build

## [0.7.36](https://github.com/skynet-gh/skylobby/releases/tag/0.7.36) - 2021-12-30

- Add setting to view windows as tabs
- Fix an issue with downloading rapid latest versions
- Rename Welcome tab to Main

## [0.7.35](https://github.com/skynet-gh/skylobby/releases/tag/0.7.35) - 2021-12-30

- Fix shell for native Windows js build

## [0.7.34](https://github.com/skynet-gh/skylobby/releases/tag/0.7.34) - 2021-12-30

- Add cache and js build to native action

## [0.7.33](https://github.com/skynet-gh/skylobby/releases/tag/0.7.33) - 2021-12-30

- Fix native action Windows version

## [0.7.32](https://github.com/skynet-gh/skylobby/releases/tag/0.7.32) - 2021-12-30

- Fix Spring picker always shown on start

## [0.7.31](https://github.com/skynet-gh/skylobby/releases/tag/0.7.31) - 2021-12-30

- Add tab to help users pick Spring directory

## [0.7.30](https://github.com/skynet-gh/skylobby/releases/tag/0.7.30) - 2021-12-29

- Fix mac release and native actions

## [0.7.29](https://github.com/skynet-gh/skylobby/releases/tag/0.7.29) - 2021-12-29

- Fix releases action

## [0.7.28](https://github.com/skynet-gh/skylobby/releases/tag/0.7.28) - 2021-12-29

- Fix some issues with detecting engine version
- Add support for 7z mod archives
- Fix port in singleplayer start script
- Fix some assumptions about old engine versions
- Attempt to improve some GitHub actions

## [0.7.27](https://github.com/skynet-gh/skylobby/releases/tag/0.7.27) - 2021-12-21

- Fix sync status not updated when battle map changes
- Fix http server backend type for web ui

## [0.7.26](https://github.com/skynet-gh/skylobby/releases/tag/0.7.26) - 2021-12-18

- Fix mac update buttons
- Add upload of mac jar without version for releases
- Fix noisy log when infolog does not exist
- Improve replays no map download message

## [0.7.25](https://github.com/skynet-gh/skylobby/releases/tag/0.7.25) - 2021-12-17

- Fix issue with auto launch engine version
- Fix some basic Mac OS X detection issues
- Add bold to discord promote message

## [0.7.24](https://github.com/skynet-gh/skylobby/releases/tag/0.7.24) - 2021-12-16

- Fix engine sync not showing source update
- Add discord promote button for metal factions
- Fix mod git versioning

## [0.7.23](https://github.com/skynet-gh/skylobby/releases/tag/0.7.23) - 2021-12-12

- Add linux jar without version in filename
- Internal build changes

## [0.7.22](https://github.com/skynet-gh/skylobby/releases/tag/0.7.22) - 2021-12-11

- Add very rudimentary support for BAR Chobby scenarios
- Fix joining passworded battles
- Fix engines list in host battle window
- Add button to close replay details
- Add discord promote button for techa hosts

## [0.7.21](https://github.com/skynet-gh/skylobby/releases/tag/0.7.21) - 2021-11-30

- Add password reset button
- Fix replay downloads from main table
- Add support for replays in .sdf format

## [0.7.20](https://github.com/skynet-gh/skylobby/releases/tag/0.7.20) - 2021-11-29

- Fix parsed replays not being saved to file
- Add setting to color your username in chat
- Make auto rejoin battle default
- Add some spacing between buttons for battle
- Add list maps button when host is a bot
- Add setting to refresh replays after game
- Fix cancelling vote message regex
- Improve engine choice download
- Fix host battle window close

## [0.7.19](https://github.com/skynet-gh/skylobby/releases/tag/0.7.19) - 2021-11-19

- Make it easier to download different engine arch
- Add handler for FRIENDREQUEST
- Set default engine download source as springrts
- Fix battle channel name to fix join leave
- Fix priorities for maps refresh

## [0.7.18](https://github.com/skynet-gh/skylobby/releases/tag/0.7.18) - 2021-11-16

- Add options for map and AI
- Fix ignore not respected for tab highlighting
- Add setting to ring on auto unspec
- Fix prioritizing battle games, add rapid priority

## [0.7.17](https://github.com/skynet-gh/skylobby/releases/tag/0.7.17) - 2021-11-13

- Fix singleplayer when no username is set
- Fix some issues with auto unspec and ready
- Add report user for teiserver
- Add cli option to set port for web ui

## [0.7.16](https://github.com/skynet-gh/skylobby/releases/tag/0.7.16) - 2021-11-12

- Fix default spring root for web ui
- Add parameter to web ui js to attempt to force cache miss
- Upload .msi again without version for direct linking

## [0.7.15](https://github.com/skynet-gh/skylobby/releases/tag/0.7.15) - 2021-11-11

- Add engine overrides to config
- Improve sync speed after downloading or force check
- Add changing usernames and passwords for servers in web ui

## [0.7.14](https://github.com/skynet-gh/skylobby/releases/tag/0.7.14) - 2021-11-11

- Add engine select when multiple for a version
- Fix state churn issue from tasks

## [0.7.13](https://github.com/skynet-gh/skylobby/releases/tag/0.7.13) - 2021-11-10

- Add launcher for web ui
- More work on web ui
- Update .desktop file with better keywords
- Add cljs compile to release action

## [0.7.12](https://github.com/skynet-gh/skylobby/releases/tag/0.7.12) - 2021-11-08

- Fix issues with singleplayer battle
- Add setting to ring after game ends
- Add basic search to settings
- Add game refresh priority for downloaded sdp
- Fix game refresh task being added multiple times
- Remove downloads from invalid sources

## [0.7.11](https://github.com/skynet-gh/skylobby/releases/tag/0.7.11) - 2021-11-07

- Add support for friends list
- Add minimap size to persistent config
- Stop rendering chat and console when server tab not selected

## [0.7.10](https://github.com/skynet-gh/skylobby/releases/tag/0.7.10) - 2021-11-06

- Rework auto resources for rapid
- Delete sdp files if they are invalid
- Update BAR server url

## [0.7.9](https://github.com/skynet-gh/skylobby/releases/tag/0.7.9) - 2021-11-06

- Fix engine download source button
- Add engine source update to auto resources

## [0.7.8](https://github.com/skynet-gh/skylobby/releases/tag/0.7.8) - 2021-11-05

- Split rapid data by spring root to prevent conflicts
- Add spring root for refresh tasks to improve speed
- Add indication when resources are refreshing
- Add icons showing resource status for battles

## [0.7.7](https://github.com/skynet-gh/skylobby/releases/tag/0.7.7) - 2021-11-02

- Fix color picker
- Attempt to fix issues with auto get resources
- Start work on lightweight web UI

## [0.7.6](https://github.com/skynet-gh/skylobby/releases/tag/0.7.6) - 2021-11-02

- Fix Add AI button disabled when it shouldn't be
- Add button to disable spads map rotate
- Make auto launch enabled by default
- Fix auto unspec not using desired ready state

## [0.7.5](https://github.com/skynet-gh/skylobby/releases/tag/0.7.5) - 2021-10-30

- Fix an issue when windows are minimzed then reopened

## [0.7.4](https://github.com/skynet-gh/skylobby/releases/tag/0.7.4) - 2021-10-29

- Move singleplayer battle to a tab

## [0.7.3](https://github.com/skynet-gh/skylobby/releases/tag/0.7.3) - 2021-10-29

- Move add bot buttons to popup window
- Add back bot name input
- Actually fix clicking old battle tabs
- Standardize font icon sizes in battle buttons

## [0.7.2](https://github.com/skynet-gh/skylobby/releases/tag/0.7.2) - 2021-10-28

- Fix issue with download source updates
- Fix issue with http download window
- Fix issue with clicking old battle tabs
- Improve visual feedback when updating downloads

## [0.7.1](https://github.com/skynet-gh/skylobby/releases/tag/0.7.1) - 2021-10-28

- Add auto complete to chat input
- Add setting to show battles you have left
- Hide SPRINGIE messages in ZK replay chat log
- Persist auto launch per server

## [0.7.0](https://github.com/skynet-gh/skylobby/releases/tag/0.7.0) - 2021-10-28

- Get auto update working on Windows

## [0.6.20](https://github.com/skynet-gh/skylobby/releases/tag/0.6.20) - 2021-10-27

- Fix chat and console auto scroll
- Add layout orientation picker for battles/users
- Switch large config files to nippy for performance

## [0.6.19](https://github.com/skynet-gh/skylobby/releases/tag/0.6.19) - 2021-10-26

- Refresh mods after rapid package update
- Add force sync check label and button
- Fix replay file association when replay has spaces
- Fix standalone replays window
- Add handler for tei battle rename

## [0.6.18](https://github.com/skynet-gh/skylobby/releases/tag/0.6.18) - 2021-10-22

- Add max tries to springfiles auto download
- Improve feedback from springfiles downloading
- Spread out auto connect and do not focus server tabs
- Move replays download to separate window

## [0.6.17](https://github.com/skynet-gh/skylobby/releases/tag/0.6.17) - 2021-10-20

- Attempt to fix issue with auto get resources and rapid
- Fix issue setting custom spring root
- Fix spring root for singleplayer
- Add controls to hide battles by status
- Add missing vote cancel spads message format
- Add sort order to servers

## [0.6.16](https://github.com/skynet-gh/skylobby/releases/tag/0.6.16) - 2021-10-15

- Fix chat tab highlight when not focused
- Stop showing battle time when started before login
- Prevent attempt to watch multiple replays
- Track spring running per battle to prevent dupe starts
- Add info message in chat when promoting to discord
- Make auto connect servers on init async
- Prevent attempt to join invalid channels

## [0.6.15](https://github.com/skynet-gh/skylobby/releases/tag/0.6.15) - 2021-10-14

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

## [0.6.14](https://github.com/skynet-gh/skylobby/releases/tag/0.6.14) - 2021-10-13

- Rework replays to save memory and startup time
- Replays will be migrated to new format on first Refresh
- Increase frequency of battle sync watchers
- Add join/leave messages for dm channels
- Display download progress during rapid update
- Improve performance of some client message handlers
- Change music to line up better with main window show

## [0.6.13](https://github.com/skynet-gh/skylobby/releases/tag/0.6.13) - 2021-10-12

- Add launchers for different max memory sizes
- Spread out initial task runs to reduce CPU on startup
- Fix battle sync check running when not needed
- Move 7z init later to improve startup time
- Remove dynamic require of ui ns to improve startup time
- Remove -server and -Xms jvm flags to attempt to improve startup time
- Remove large icons to reduce per-window memory footprint

## [0.6.12](https://github.com/skynet-gh/skylobby/releases/tag/0.6.12) - 2021-10-10

- Add mute for rings to the right of battle chat input
- Track user away time and display as tooltip
- Add button to cancel a queued task
- Add button to stop a running task, best to only be used if task is hung

## [0.6.11](https://github.com/skynet-gh/skylobby/releases/tag/0.6.11) - 2021-10-10

- Fix NPE in chat and console tabs

## [0.6.10](https://github.com/skynet-gh/skylobby/releases/tag/0.6.10) - 2021-10-09

- Fix singleplayer game not starting when spec
- Fix minimap image not showing after download

## [0.6.9](https://github.com/skynet-gh/skylobby/releases/tag/0.6.9) - 2021-10-09

- Fix server tab highlight on muted battle

## [0.6.8](https://github.com/skynet-gh/skylobby/releases/tag/0.6.8) - 2021-10-09

- Move battle votes below map in vertical layout
- Add highlighting for battle chat
- Add settings to control both battle and chat highlighting
- Add muting of individual chats for highlighting

## [0.6.7](https://github.com/skynet-gh/skylobby/releases/tag/0.6.7) - 2021-10-09

- Fix chat focus tracking

## [0.6.6](https://github.com/skynet-gh/skylobby/releases/tag/0.6.6) - 2021-10-09

- Make selected tabs per server
- Fix ba and spring official server names
- Make black css theme the default
- Fix minimap teams not using inc ids setting
- Fix chat and console reuse when server tab positions change

## [0.6.5](https://github.com/skynet-gh/skylobby/releases/tag/0.6.5) - 2021-10-08

- Add tab highlighting on new chats

## [0.6.4](https://github.com/skynet-gh/skylobby/releases/tag/0.6.4) - 2021-10-08

- Add tab highlighting on new chats
- Fix message context menu not working
- Fix minimap display for non-boxes and in replays
- Improve replay details parsing and caching
- Move all map images to files, improving performance and reducing cache size

## [0.6.3](https://github.com/skynet-gh/skylobby/releases/tag/0.6.3) - 2021-10-07

- Add cooldown to rapid auto download tries
- Fix rapid download engine file choice
- Fix ba github download source
- Reduce ui context cache size

## [0.6.2](https://github.com/skynet-gh/skylobby/releases/tag/0.6.2) - 2021-10-07

- Fix ready change in battle
- Fix singleplayer battle
- Fix some issues with auto get resources and sync status update
- Increase max heap size to 2GB

## [0.6.1](https://github.com/skynet-gh/skylobby/releases/tag/0.6.1) - 2021-10-07

- Add setting for focusing chat on new message
- Fix auto resources sometimes not running
- Add setting to join battles as a player
- Add cache for ui context to prevent OOM issue
- Lower cache sizes for maps, mods, replays
- Increase max heap size to 1.5G

## [0.6.0](https://github.com/skynet-gh/skylobby/releases/tag/0.6.0) - 2021-10-06

- Switch UI to use context for better performance

## [0.5.9](https://github.com/skynet-gh/skylobby/releases/tag/0.5.9) - 2021-10-04

- Fix issue with config validation

## [0.5.8](https://github.com/skynet-gh/skylobby/releases/tag/0.5.8) - 2021-10-04

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

## [0.5.7](https://github.com/skynet-gh/skylobby/releases/tag/0.5.7) - 2021-10-03

- Add setting to hide vote chat messages
- Fix some host message and vote display issues
- Fix windows sometimes getting zero size
- Fix multiple auto download from springfiles
- Move user ignore to bottom of context menu
- Add setting to start numbering player and team ids at 1
- Fix auto get resources setting and add to battle resources panel

## [0.5.6](https://github.com/skynet-gh/skylobby/releases/tag/0.5.6) - 2021-10-02

- Add parsing of battle host messages
- Add battle votes panel
- Add setting to hide battle host messages by type
- Add port setting for battle hosting
- Make ring specs a task and disable button when running
- Improve rapid, download, and import windows
- Fix a number of reflection issues (performance)

## [0.5.5](https://github.com/skynet-gh/skylobby/releases/tag/0.5.5) - 2021-09-30

- Add support for STARTTLS
- Fix popout battle chat bleeding servers
- Add resource buttons below sync panes on battle
- Improve performance of replay filtering
- Fix another case where state watchers ran immediately
- Ensure all client-server messages are in console log
- Fix battle popout chat divider position npe
- Fix battle status sending empty color

## [0.5.4](https://github.com/skynet-gh/skylobby/releases/tag/0.5.4) - 2021-09-28

- Fix table sorting when items change
- Make players table resizing unconstrained
- Fix periodic state change watchers firing immediately
- Add setting to show team skill sums

## [0.5.3](https://github.com/skynet-gh/skylobby/releases/tag/0.5.3) - 2021-09-28

- Add setting to show battle below battles again
- Attempt to fix dropdown position issue
- Workaround for SSL login issue

## [0.5.2](https://github.com/skynet-gh/skylobby/releases/tag/0.5.2) - 2021-09-27

- Save window maximized states
- Reduce frequency of window state sync

## [0.5.1](https://github.com/skynet-gh/skylobby/releases/tag/0.5.1) - 2021-09-27

- Fix unable to stop auto unspec

## [0.5.0](https://github.com/skynet-gh/skylobby/releases/tag/0.5.0) - 2021-09-27

- Improve battle layout
- Add auto download maps from springfiles
- Make battle a tab in main window
- Add buttons to open windows for battles and chats
- Add refresh download source for engine button

## [0.4.18](https://github.com/skynet-gh/skylobby/releases/tag/0.4.18) - 2021-09-26

- Fix client id not set

## [0.4.17](https://github.com/skynet-gh/skylobby/releases/tag/0.4.17) - 2021-09-26

- Add support for client id other than zero
- Add backup of infologs
- Add promote button in battle
- Add support for user agent string override
- Fix text selection in chat and console on new content
- Fix issue with chat tab contents

## [0.4.16](https://github.com/skynet-gh/skylobby/releases/tag/0.4.16) - 2021-09-25

- Fix issues with chat auto scroll
- Fix issue with unready after game
- Fix unready on unspec and auto unspec
- Improve performance from window size and position saving
- Add saving of battle divider position
- Use random open port for singleplayer
- Decrease auto unspec cooldown
- Add BA github repo as games source
- Fix multiplayer buttons when a singleplayer battle is open

## [0.4.15](https://github.com/skynet-gh/skylobby/releases/tag/0.4.15) - 2021-09-19

- Improve chat auto scroll
- Improve performance of chat and console rendering

## [0.4.14](https://github.com/skynet-gh/skylobby/releases/tag/0.4.14) - 2021-09-19

- Add auto rejoin battle setting
- Add ring sound and volume settings
- Clean up battles buttons, host battle in its own window
- Save most window sizes and locations

## [0.4.13](https://github.com/skynet-gh/skylobby/releases/tag/0.4.13) - 2021-09-17

- Add autounspec when teamsize is changed

## [0.4.12](https://github.com/skynet-gh/skylobby/releases/tag/0.4.12) - 2021-09-15

- Add engine BAR105 special case
- Add setting for ready on unspec
- Fix ring specs happening on render thread
- Add count to replays view

## [0.4.11](https://github.com/skynet-gh/skylobby/releases/tag/0.4.11) - 2021-09-12

- Fix infinite loop in map and mod refresh when errors reading
- Fix auto unspec when a player leaves a battle

## [0.4.10](https://github.com/skynet-gh/skylobby/releases/tag/0.4.10) - 2021-09-11

- Fix infinite loop in map refresh with .sdd dirs
- Fix non-.sdd dirs in games being scanned
- Fix disable-tasks setting not being persisted

## [0.4.9](https://github.com/skynet-gh/skylobby/releases/tag/0.4.9) - 2021-09-10

- Add auto unspec checkbox
- Add highlight of username and custom words in chat
- Add logging of chat to files in `.skylobby/chat-logs`
- Fix unready after game using state when game started
- Add Ring Specs button
- Improve rendering performance of replays window
- General performance improvements
- Lower threshold for chat auto scroll
- Filter BarManager messages from chat

## [0.4.8](https://github.com/skynet-gh/skylobby/releases/tag/0.4.8) - 2021-08-29

- Add ignore user to context menu
- Add join and leave to chat

## [0.4.7](https://github.com/skynet-gh/skylobby/releases/tag/0.4.7) - 2021-08-21

- Update springfiles url to springfiles.springrts.com

## [0.4.6](https://github.com/skynet-gh/skylobby/releases/tag/0.4.6) - 2021-08-11

- Add download sources for Total Atomization Prime releases and maps

## [0.4.5](https://github.com/skynet-gh/skylobby/releases/tag/0.4.5) - 2021-07-13

- Fix unready after game setting
- Stop sending !joinas spec for most servers

## [0.4.4](https://github.com/skynet-gh/skylobby/releases/tag/0.4.4) - 2021-07-11

- Fix performance issue with chat and console
- Make unready after game a setting
- Fix battle layout and player colors settings not saved

## [0.4.3](https://github.com/skynet-gh/skylobby/releases/tag/0.4.3) - 2021-07-09

- Fix game specific settings backup and restore
- Fix replay chat spec player colors

## [0.4.2](https://github.com/skynet-gh/skylobby/releases/tag/0.4.2) - 2021-07-08

- Add option to auto backup/restore Spring settings by game type

## [0.4.1](https://github.com/skynet-gh/skylobby/releases/tag/0.4.1) - 2021-07-08

- Scroll chat and console to bottom on create
- Make auto scroll threshold based on component height
- Fix replay players table columns
- Add color to replay chat log

## [0.4.0](https://github.com/skynet-gh/skylobby/releases/tag/0.4.0) - 2021-07-07

- Select text in chat without mode change
- Auto scroll chat when at bottom, lock when scrolling up
- Add setting to hide player table columns
- Unready after game ends
- Add color to console

## [0.3.66](https://github.com/skynet-gh/skylobby/releases/tag/0.3.66) - 2021-06-26

- Fix git mod version sync
- Change here/away and play/spec to dropdowns
- Add up/down to fill previous chat messages
- Add CSS classes for some parts of chat
- Add setting to auto refresh replays

## [0.3.65](https://github.com/skynet-gh/skylobby/releases/tag/0.3.65) - 2021-06-23

- Add setting for git mod versioning

## [0.3.64](https://github.com/skynet-gh/skylobby/releases/tag/0.3.64) - 2021-06-23

- Fix register response message display
- Fix login error message for token auth

## [0.3.63](https://github.com/skynet-gh/skylobby/releases/tag/0.3.63) - 2021-06-22

- Add setting for chat font size
- Make start game sync match protocol sync status
- Re-enable matchmaking queue updates
- Fix token auth for SSL servers

## [0.3.62](https://github.com/skynet-gh/skylobby/releases/tag/0.3.62) - 2021-06-16

- Fix double click to open chat with user
- Fix auto scroll in popout chat window

## [0.3.61](https://github.com/skynet-gh/skylobby/releases/tag/0.3.61) - 2021-06-15

- Add battle chat window popout option
- Fix singleplayer battle
- Fix engine AIs
- Fix minimap scale issues
- Allow text input for spring root
- Disable matchmaking update until server is fixed

## [0.3.60](https://github.com/skynet-gh/skylobby/releases/tag/0.3.60) - 2021-06-14

- Add battle layout select for vertical or horizontal chat
- Add minimap size select
- Fix some render performance issues
- Fix matchmaking protocol
- Move matchmaking to a tab

## [0.3.59](https://github.com/skynet-gh/skylobby/releases/tag/0.3.59) - 2021-06-13

- Fix replay refresh for real

## [0.3.58](https://github.com/skynet-gh/skylobby/releases/tag/0.3.58) - 2021-06-13

- Fix replay refresh

## [0.3.57](https://github.com/skynet-gh/skylobby/releases/tag/0.3.57) - 2021-06-13

- Fix replay loading stuck on invalid files
- Fix map directories not being reloaded
- Fix duplicate maps not being removed

## [0.3.56](https://github.com/skynet-gh/skylobby/releases/tag/0.3.56) - 2021-06-12

- Display multiple download sources for maps
- Separate bot and human user counts

## [0.3.55](https://github.com/skynet-gh/skylobby/releases/tag/0.3.55) - 2021-06-10

- Fix agreement display and confirm

## [0.3.54](https://github.com/skynet-gh/skylobby/releases/tag/0.3.54) - 2021-06-08

- Update BAR server url
- Add BAR SSL server

## [0.3.53](https://github.com/skynet-gh/skylobby/releases/tag/0.3.53) - 2021-06-08

- Save and set preferred faction by game type
- Default ally to 0
- Add BAR GitHub maps download source
- Fix CSS for matchmaking window
- Add mod version and name without version to index
- Fix `CHANNEL` handler when no trailing whitespace
- Fix `CLIENTS` handler when no clients

## [0.3.52](https://github.com/skynet-gh/skylobby/releases/tag/0.3.52) - 2021-06-06

- Add download source for EvoRTS TAP GitHub release artifacts
- Add handler for `JOINBATTLEREQUEST`
- Add mod dependency for EvoRTS on its music mod
- Lighten shadow around player names
- Increase font size in chat
- Add resource buttons to singleplayer battle

## [0.3.51](https://github.com/skynet-gh/skylobby/releases/tag/0.3.51) - 2021-05-31

- Add shadow around player names
- Add option to interleave player ids in balance

## [0.3.50](https://github.com/skynet-gh/skylobby/releases/tag/0.3.50) - 2021-05-28

- Revert per-user install for Windows
- Add jvm flags for dpi scaling on Windows
- Fix factions for S44

## [0.3.49](https://github.com/skynet-gh/skylobby/releases/tag/0.3.49) - 2021-05-26

- Release to test auto updates

## [0.3.48](https://github.com/skynet-gh/skylobby/releases/tag/0.3.48) - 2021-05-26

- Fix mod update loop caused by re-scanning directories
- Fix auto update for installed exe

## [0.3.47](https://github.com/skynet-gh/skylobby/releases/tag/0.3.47) - 2021-05-25

- Set per-user install for Windows

## [0.3.46](https://github.com/skynet-gh/skylobby/releases/tag/0.3.46) - 2021-05-25

- Release to test auto updates

## [0.3.45](https://github.com/skynet-gh/skylobby/releases/tag/0.3.45) - 2021-05-25

- Add button to auto download updates and restart

## [0.3.44](https://github.com/skynet-gh/skylobby/releases/tag/0.3.44) - 2021-05-25

- Fix music auto play
- Add battle players color names by their personal color
- Fix singleplayer balance and fix colors
- Add colors from SPADS for different setups

## [0.3.43](https://github.com/skynet-gh/skylobby/releases/tag/0.3.43) - 2021-05-25

- Add battle run time display
- Add pause and resume of lobby music when in game
- Filter out non-playable files from music folder
- Fix `--music-volume` cli option and improve cli error messages

## [0.3.42](https://github.com/skynet-gh/skylobby/releases/tag/0.3.42) - 2021-05-23

- Add music player, `--music-dir`, and `--music-volume` cli options
- Fix clear start boxes
- Contain start boxes dragging to minimap
- Fix AI id in replay players table
- Change slowest state watchers to periodic

## [0.3.41](https://github.com/skynet-gh/skylobby/releases/tag/0.3.41) - 2021-05-22

- Add setting to disable tasks while in a game, for performance
- Fix battle hosting
- Add filtering for hosting replay files
- Add replay filtering by game id
- Improve replay view for online replays
- Switch auto resources to periodic for performance

## [0.3.40](https://github.com/skynet-gh/skylobby/releases/tag/0.3.40) - 2021-05-20

- Fix ingame status not set correctly
- Fix replay parsing sometimes causing memory issues

## [0.3.39](https://github.com/skynet-gh/skylobby/releases/tag/0.3.39) - 2021-05-16

- Fix auto launch detecting spectator incorrectly
- Stop sending `!joinas spec` when no script password
- Fix resource details caches not sorted

## [0.3.38](https://github.com/skynet-gh/skylobby/releases/tag/0.3.38) - 2021-05-14

- Add chat log to replay details view

## [0.3.37](https://github.com/skynet-gh/skylobby/releases/tag/0.3.37) - 2021-05-12

- Make auto launch only apply while spectating
- Add replay buttons to open folder or BAR online
- Fix rare issue with players table in replays

## [0.3.36](https://github.com/skynet-gh/skylobby/releases/tag/0.3.36) - 2021-05-11

- Add tag string field for replays
- Add dedupe of replays by id
- Fix filter by player name for online replays
- Add `--no-update-check` cli flag for embedded skylobby

## [0.3.35](https://github.com/skynet-gh/skylobby/releases/tag/0.3.35) - 2021-05-09

- Enable/disable chat auto scroll when at bottom/scrolled up
- Attempt to fix issue with chat moving on new message when scrolled up

## [0.3.34](https://github.com/skynet-gh/skylobby/releases/tag/0.3.34) - 2021-05-07

- Fix resource churn for replays and battle mods

## [0.3.33](https://github.com/skynet-gh/skylobby/releases/tag/0.3.33) - 2021-05-06

- Add rapid update to auto resource tasks
- Add uncaught error logging handler
- Fix modoptions in singleplayer
- Make rapid package update message more clear

## [0.3.32](https://github.com/skynet-gh/skylobby/releases/tag/0.3.32) - 2021-05-04

- Fix modoptions missing
- Add map description

## [0.3.31](https://github.com/skynet-gh/skylobby/releases/tag/0.3.31) - 2021-05-02

- Fix occasional crash due to profiler concurrency
- Fix battle map suggest

## [0.3.30](https://github.com/skynet-gh/skylobby/releases/tag/0.3.30) - 2021-05-02

- Fix ready checkbox not doing anything
- Fix selected replay when opening from file
- Fix bot version display when changing bot name
- Add button to connect to auto-connect servers

## [0.3.29](https://github.com/skynet-gh/skylobby/releases/tag/0.3.29) - 2021-05-02

- Add resource sync to left of map in replay view
- Fix typo causing map download progress to be hidden
- Fix replay sync springfiles download
- Fix selected replay watching

## [0.3.28](https://github.com/skynet-gh/skylobby/releases/tag/0.3.28) - 2021-05-01

- Fix replay watching
- Add `--css-file FILE` CLI option to set custom CSS using a file
- Add `--css-preset PRESET` CLI option to set a CSS preset theme
- Add `--replay-source DIR` CLI option to replace default replay sources with a custom list
- Add `--window-maximized` CLI option to start the main window maximized

## [0.3.27](https://github.com/skynet-gh/skylobby/releases/tag/0.3.27) - 2021-05-01

- Add /rename chat command
- Add handling for legacy battle chat
- Set ready and auto launch when playing
- Fix background color css issue
- Fix some windows not limited to screen size
- Fix handler for ADDUSER in some cases

## [0.3.26](https://github.com/skynet-gh/skylobby/releases/tag/0.3.26) - 2021-04-30

- Add file association for .sdfz
- Add launcher for skyreplays
- Fix display of modoption sections
- Decouple battle ready from auto start
- Add IPC server to open replay in running process
- Add support for css custom style

## [0.3.25](https://github.com/skynet-gh/skylobby/releases/tag/0.3.25) - 2021-04-29

- Add support for bridged chat
- Add custom CSS section to settings

## [0.3.24](https://github.com/skynet-gh/skylobby/releases/tag/0.3.24) - 2021-04-28

- Fix balance button and minimap type combo box
- Fix battles and users filter cli args
- Add springfiles download for maps

## [0.3.23](https://github.com/skynet-gh/skylobby/releases/tag/0.3.23) - 2021-04-28

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

## [0.3.22](https://github.com/skynet-gh/skylobby/releases/tag/0.3.22) - 2021-04-28

- Add server auto connect option
- Add `--chat-channel` cli arg

## [0.3.21](https://github.com/skynet-gh/skylobby/releases/tag/0.3.21) - 2021-04-28

- Fix `--skylobby-root` not coerced to file

## [0.3.20](https://github.com/skynet-gh/skylobby/releases/tag/0.3.20) - 2021-04-27

- Add flag icons for countries
- Add `--skylobby-root` to set skylobby config and log directory
- Fix auto download which broke after multi spring dirs

## [0.3.19](https://github.com/skynet-gh/skylobby/releases/tag/0.3.19) - 2021-04-27

- Greatly improve performance by making some state watchers periodic
- Fix resource details cache empty key issue for map and mod sync

## [0.3.18](https://github.com/skynet-gh/skylobby/releases/tag/0.3.18) - 2021-04-27

- Fix map details not loading automatically

## [0.3.17](https://github.com/skynet-gh/skylobby/releases/tag/0.3.17) - 2021-04-27

- Fix use of deprecated api removed in Java 16
- Add max tries and retry button for battle map details
- Fix username and password with `--server-url` arg

## [0.3.16](https://github.com/skynet-gh/skylobby/releases/tag/0.3.16) - 2021-04-26

- Add status icon for player sync
- Add chat commands for /msg and /ingame
- Add CLI flag `--server-url` to set selected server

## [0.3.15](https://github.com/skynet-gh/skylobby/releases/tag/0.3.15) - 2021-04-25

- Add CLI flag `--spring-root`
- Send actual sync status to server
- Fix concurrency issue writing configs
- Re-enable Windows jar upload

## [0.3.14](https://github.com/skynet-gh/skylobby/releases/tag/0.3.14) - 2021-04-24

- Fix issue with map and mod details when changing spring dir
- Use file pickers for dir settings
- Show spring directories on main screen
- Fix team number buttons
- Fix map filtering in singleplayer battle
- Improve layout of welcome page when in singleplayer battle
- Fix singleplayer battle engine/mod/map pickers set global choice

## [0.3.13](https://github.com/skynet-gh/skylobby/releases/tag/0.3.13) - 2021-04-23

- Improve feedback for when downloads and imports start
- Fix replays rapid download and git version action
- Fix rapid download window
- Fix SpringFiles download button appearing before doing a search
- Add more unit tests

## [0.3.12](https://github.com/skynet-gh/skylobby/releases/tag/0.3.12) - 2021-04-22

- Fix resources not being reloaded in all Spring directories
- Add balance and fixcolors buttons
- Fix an issue starting a game from git

## [0.3.11](https://github.com/skynet-gh/skylobby/releases/tag/0.3.11) - 2021-04-20

- Fix dependency issue with async library

## [0.3.10](https://github.com/skynet-gh/skylobby/releases/tag/0.3.10) - 2021-04-20

- Fix jar main class
- Fix battle map and mod details retry

## [0.3.9](https://github.com/skynet-gh/skylobby/releases/tag/0.3.9) - 2021-04-20

- Fix chat in pop out battle window
- Add support for teiserver token auth
- Fix matchmaking after multi server
- Fix battle map and mod sync after multi server

## [0.3.8](https://github.com/skynet-gh/skylobby/releases/tag/0.3.8) - 2021-04-20

- Fix circular dependency issue in installer
- Fix minimap size when loading
- Add ability to retry rapid download in battle view

## [0.3.7](https://github.com/skynet-gh/skylobby/releases/tag/0.3.7) - 2021-04-20

- Add support for TLS communication to the server

## [0.3.6](https://github.com/skynet-gh/skylobby/releases/tag/0.3.6) - 2021-04-20

- Add config in server window to set set Spring directory
- Add ability to login to same server as multiple users
- Add slider to shrink battles table when in a battle

## [0.3.5](https://github.com/skynet-gh/skylobby/releases/tag/0.3.5) - 2021-04-18

- Fix task workers not starting
- Fix resource details update spawning a thread
- Improve table column sizing

## [0.3.4](https://github.com/skynet-gh/skylobby/releases/tag/0.3.4) - 2021-04-17

- Rework task system to improve responsiveness
- Split mod refresh task into chunks
- Fix a parsing operation in render loop
- Fix chat mode change

## [0.3.3](https://github.com/skynet-gh/skylobby/releases/tag/0.3.3) - 2021-04-16

- Fix battle view render error
- Fix start positions in singleplayer

## [0.3.2](https://github.com/skynet-gh/skylobby/releases/tag/0.3.2) - 2021-04-16

- Add basic support for spring settings backup and restore
- Fix console commands
- Fix chat and channels
- Fix app close

## [0.3.1](https://github.com/skynet-gh/skylobby/releases/tag/0.3.1) - 2021-04-16

- Fix console not showing
- Add Hakora site as source for maps
- Stop building skyreplays release artifacts

## [0.3.0](https://github.com/skynet-gh/skylobby/releases/tag/0.3.0) - 2021-04-15

- Add ability to connect to multiple servers simultaneously
- Add local battle separate from multiplayer
- Improve map and game caching
- Add .sdd map handling
- Add test coverage

## [0.2.15](https://github.com/skynet-gh/skylobby/releases/tag/0.2.15) - 2021-04-12

- Add filtering by replay source

## [0.2.14](https://github.com/skynet-gh/skylobby/releases/tag/0.2.14) - 2021-04-12

- Improve feedback for downloading replays
- Add recursive replay sources

## [0.2.13](https://github.com/skynet-gh/skylobby/releases/tag/0.2.13) - 2021-04-12

- Greatly improved performance
- Add download of BAR replays
- Add workaround for BAR sidedata change
- Download resources from springfiles by search
- Update matchmaking to new protocol.

## [0.2.12](https://github.com/skynet-gh/skylobby/releases/tag/0.2.12) - 2021-04-11

- Add color mode to chat channels
- Improve coloring in battles table, bold players
- Add skylobby update check

## [0.2.11](https://github.com/skynet-gh/skylobby/releases/tag/0.2.11) - 2021-04-11

- Make auto download battle resources default
- Fix skill uncertainty in replays view
- Fix triple click on battle joining then leaving
- Add display of player counts in battle (e.g. 2v2)
- Add support for teiserver matchmaking

## [0.2.10](https://github.com/skynet-gh/skylobby/releases/tag/0.2.10) - 2021-04-10

- Improve main window layout at small sizes
- Improve battle resource sync panes and auto download
- Improve replay map and mod detail updating
- Add license file

## [0.2.9](https://github.com/skynet-gh/skylobby/releases/tag/0.2.9) - 2021-04-09

- Add singleplayer battle
- Add option to auto download battle resources
- Improve performance when changing spring isolation dir
- Improve startup performance by delaying jobs
- Cache rapid file scan based on last modified
- Tweak jvm args for performance

## [0.2.8](https://github.com/skynet-gh/skylobby/releases/tag/0.2.8) - 2021-04-08

- Add back Linux jar building

## [0.2.7](https://github.com/skynet-gh/skylobby/releases/tag/0.2.7) - 2021-04-08

- Fix spring isolation dir config ignored in some places
- Fix engine executables not being set to executable (Linux)
- Fix pr-downloader location in some older engines
- Fix register confirm agreement not using verification code
- Fix chat channels sharing draft message
- Add check box to disable chat and console auto scroll
- Add color for some status icons
- Add map size, metalmap, and heightmap to replays window
- Stop uploading build jars to release, installers only

## [0.2.6](https://github.com/skynet-gh/skylobby/releases/tag/0.2.6) - 2021-04-07

- Initial public release

## [0.2.5](https://github.com/skynet-gh/skylobby/releases/tag/0.2.5) - 2021-04-07

- Fix Windows packaging

## [0.2.4](https://github.com/skynet-gh/skylobby/releases/tag/0.2.4) - 2021-04-07

- Fix both jars included in replays installer

## [0.2.3](https://github.com/skynet-gh/skylobby/releases/tag/0.2.3) - 2021-04-07

- Simplify and reorder battles and players tables
- Fix BAR location on Linux
- Fix hitching due to task workers doing unneeded writes

## [0.2.2](https://github.com/skynet-gh/skylobby/releases/tag/0.2.2) - 2021-04-07

- Add ring handler and sound effect

## [0.2.1](https://github.com/skynet-gh/skylobby/releases/tag/0.2.1) - 2021-04-06

- Add setting to change Spring directory

## [0.2.0](https://github.com/skynet-gh/skylobby/releases/tag/0.2.0) - 2021-04-06

- Add standalone replays packages
- Add !cv start when playing but not host
- Sort battle players list same as replays
- Fix chat messages
- Fix script password in JOINBATTLE
- Fix game launching when not ready or resources missing
- Fix battle map change detection
- Fix windows changing order when opened/closed
- Fix replays window skill including spectators

## [0.1.7] - 2021-04-06

- Add script password handling
- Improve installer with version so upgrade works
- Add app icon
- Save login per server
- Add settings window with custom import and replay paths
- Fix tab switch on new chat
- Fix springfightclub compatibility by adding more comp flags
- Fix some issues with battle resources not refreshing
- Change windows installer to .msi

## [0.1.6] - 2021-04-04

- Fix npe on Linux due to JavaFX TabPane selection model differences

## [0.1.5] - 2021-04-04

- Add direct messaging
- Add console tab to send raw commands
- Fix game not starting when host starts
- Add skill filtering to replays window
- Fix some table sorting issues

## [0.1.4] - 2021-04-03

- Rename to skylobby
- Fix sidedata hardcoded for BA and BAR (now supports Metal Factions)
- Fix windows being larger than screen
- Improve replays viewer: remove invalid, fix watch status, filter terms with whitespace

## [0.1.3] - 2021-04-01

- Store replay details in a file and track watched status
- Filter replays by game type and number of players
- Add progress indicators for replay resource buttons
- Improve performance for downloads and imports

## [0.1.2] - 2021-03-31

- Replay watcher
- Starting position rectangles for choose in game
- Chat
- Store multiple servers and login per server

## [0.1.1] - 2020-12-28

- Add ability to register on server

## [0.1.0] - 2020-12-27

Initial public release.
- GitHub Actions to publish releases and build jars and installers
- GitHub Pages for dev blog and user guide
- GitHub Actions for unit tests
- Switch map load to fast 7zip
- Map detail browser with minimap images
- Download BAR engine from GitHub

## [0.0.3] - 2020-12-17

Fairly usable now.
- Manage resources (engines, mods/games, maps).
- Add many paths to get resources: varous http sites, rapid
- Parse modinfo
- Parse minimap and metal map directly from files
- Start games from app directory without resource copying
- Fully flesh out battles
  - Add minimap, start positions
  - Support all battle status changes in protocol

## [0.0.2] - 2020-09-07

Start basic games.
- Run Spring in isolation mode
- Fix hard coding username, password
- Add bots to battles
- Add basic battle status

## [0.0.1] - 2020-07-26

Basic UI and client.
- Initial UI
- Basic communication with Uberserver
  - Login, battles
- Ability to run spring with script.txt
