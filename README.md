# Random Door Portals

A server-side Fabric mod for Minecraft **1.21.1**: walk through any open registered door and
you emerge from a random different registered door somewhere in the **same dimension**.
Vanilla clients need no mod installed.

- Author: WinniePatGG
- Version: 1.0.0
- License: MIT (see `LICENSE.txt`)
- Package: `de.winniepat.randomdoorportals`

## What it does

- Every double door in an enabled dimension is a potential portal endpoint.
- Doors are discovered automatically:
  - when a chunk is loaded (budgeted background scan, also covers the door blocks of
    villages and other naturally generated structures), and
  - on the first time a player physically opens it (covers doors placed while the chunk
    has been running without a reload).
- Walking through an **open** door (physically crossing the doorway cell) teleports you
  to a random other registered door in that dimension.
- The destination door can also be entered normally - it is a portal only when you walk
  through it.

There are no gods and no config GUI; everything is one JSON file and two commands.

## Requirements

| Thing                      | Version                    |
|----------------------------|----------------------------|
| Minecraft                  | 1.21.1                     |
| Fabric Loader              | >= 0.16.0                  |
| Fabric API                 | any current 1.21.x build   |
| Java                       | 21                         |
| Client install             | **not required** (vanilla client works) |

## Installing on a server

1. Copy `randomdoorportals-1.0.0.jar` into the server's `mods/` folder - **only on the
   logical server** (in single player: into the instance's `mods/`; vanilla multiplayer
   servers: server `mods/` only).
2. Start the server. `config/randomdoorportals.json` is created with defaults.
3. Load chunks with doors in them (walk around or let players connect). `/rdp status`
   shows how many doors are registered per dimension.

## Commands

| Command        | Permission | Effect                                                        |
|----------------|------------|---------------------------------------------------------------|
| `/rdp status`  | everyone   | Registered door count per dimension + active config summary.  |
| `/rdp reload`  | ops (lvl 2)| Re-reads `config/randomdoorportals.json` without restarting.  |

## How crossing detection works (in short)

- Every tick, each player's previous/new position segment is checked against the doors
  near them (the doors are indexed per chunk, so only a handful of candidates are tested).
- A segment counts as *through the doorway* only when it crosses the door cell's
  mid-plane, the crossing point lies inside the door cell horizontally and inside the
  two-block doorway band vertically, the door is open (configurable), and the door's
  chunk is loaded. Segments longer than 4 blocks are treated as teleports instead.
- Safety: teleports use the vanilla same-dimension `ServerPlayerEntity.teleport(...)` path
  (post-teleport loading ticket included) and never force-load anything (candidate, arrival
  and destination chunks are all verified to be loaded first).
- Arrival spot search prefers the facing side of the destination door, then the opposite
  side, then both perpendicular sides, then a one-block lower/higher fallback; each spot is
  checked for collisions, world border, world height, living entities inside the player
  box, floor availability and - if `dangerProtection` is on - fluids, fire, magma, cobwebs,
  powder snow, cactus, campfires, berry bushes, dripstone and wither roses.
- Anti-flicker: per-player teleport cooldown, plus "avoid repeating" memory of the last
  entrance/destination doors when other candidates exist.
- Sizes are bounded: chunks per tick, milliseconds per tick and queue length come from the
  config; a rolling validation sweep re-checks a few registered doors per tick and cleans
  up entries whose block disappeared while nobody was looking.

## Persistence

Door lists are stored as vanilla **persistent state** per dimension
(`data/<dimension>/randomdoorportals_doors.dat`, a NBT long array of lower door block
positions). They are saved together with the world (and marked dirty immediately on every
change), so a restart keeps every discovered door. Doors that were removed while their
chunk was unloaded are cleaned up on the next chunk load.

## Configuration

`config/randomdoorportals.json` (pretty-printed, written on first start):

```json
{
  "enabled": true,
  "allowOverworld": true,
  "allowNether": true,
  "allowEnd": true,
  "cooldownTicks": 40,
  "minDistanceBlocks": 0.0,
  "maxDistanceBlocks": 0.0,
  "requireEntranceOpen": true,
  "requireDestinationOpen": true,
  "dangerProtection": true,
  "avoidRepeatDestinations": true,
  "chunkScanBudget": 2,
  "scanTimeBudgetMillis": 4,
  "maxQueueSize": 1024,
  "particlesEnabled": true,
  "soundsEnabled": true,
  "debugLogging": false
}
```

| Field                      | Default | Meaning                                                                 |
|----------------------------|---------|-------------------------------------------------------------------------|
| `enabled`                  | `true`  | Master switch; `false` disables everything.                              |
| `allowOverworld`/`allowNether`/`allowEnd` | `true` | Teleporting + scanning per vanilla dimension. Custom dimensions follow `allowOverworld`. |
| `cooldownTicks`            | `40`    | Per-player tick cooldown between teleports (0..6000).                    |
| `minDistanceBlocks`        | `0.0`   | Minimum horizontal distance entrance\~destination (0 = off).             |
| `maxDistanceBlocks`        | `0.0`   | Maximum horizontal distance entrance\~destination (0 = unlimited).       |
| `requireEntranceOpen`      | `true`  | The entrance must be open when crossed.                                   |
| `requireDestinationOpen`   | `true`  | Closed destination doors are not chosen.                                  |
| `dangerProtection`         | `true`  | Reject arrival spots near fluids/fire/hazard blocks/void.                 |
| `avoidRepeatDestinations`  | `true`  | Avoid the previous entrance/destination doors when others exist.          |
| `chunkScanBudget`          | `2`     | Max chunks scanned per server tick (1..1024).                             |
| `scanTimeBudgetMillis`     | `4`     | Max milliseconds of scanning per server tick (1..50).                     |
| `maxQueueSize`             | `1024`  | Max queued chunk scans (16..1,000,000); beyond that new loads are skipped. |
| `particlesEnabled`         | `true`  | Portal particles at entrance and arrival.                                 |
| `soundsEnabled`            | `true`  | Portal travel sound at both ends.                                         |
| `debugLogging`             | `false` | Verbose per-crossing/per-scan logs.                                       |

Invalid values are clamped into their ranges; a corrupt file is backed up as
`randomdoorportals.broken.json` and defaults are used until you fix it.

## Building from source

Windows (PowerShell or cmd):

```bat
gradlew.bat build
gradlew.bat test
```

Linux/macOS:

```sh
./gradlew build
./gradlew test
```

Output: `build/libs/randomdoorportals-1.0.0.jar` (+ `-sources.jar`).
The unit tests in `src/test/java` are pure Java and run without Minecraft.

Toolchain used for development: Gradle wrapper 9.8.0 (`gradle.properties`/wrapper included),
Fabric Loom 1.17.21, mappings `1.21.1+build.3`, loader 0.20.1, Fabric API `0.116.17+1.21.1`.

## Known limitations

- Same dimension only (by design) - a door in the Nether never connects to the Overworld.
- Only door **blocks** (`DoorBlock`) matter; double-height open trapdoors etc. are not portals.
- A player flying with an elytra does not trigger portals (processed as a teleport jump);
  spectators never trigger portals.
- Walking through a door while *falling* (e.g. from above through an open doorway) counts
  as crossing.
- Doors opened only by redstone are registered on their next physical use or their next
  chunk load - an idle chunk with never-touched doors does not know them yet.
- Riders are dismounted by the vanilla teleport path (boats, minecarts, horses).
- The teleport chance is a random draw over the whole dimension; very distant chunks may
  take a moment to rescan after they are gone from disk cache (their entries persist until
  the chunk reload proves them wrong).

## Project layout

```
src/main/java/de/winniepat/randomdoorportals/
  RandomDoorPortals.java      entrypoint, event wiring
  config/PortalConfig.java    JSON config with clamps + backup
  portal/DoorGeometry.java    pure key encoding + plane crossing math (unit tested)
  registry/DoorRegistry.java  per-dimension state holder
  registry/PortalDoorState.java persistent NBT state + chunk index
  scanning/DoorScanner.java   budgeted chunk queue, scans, validation sweep
  tracking/PlayerPositionTracker.java per-tick prev/new positions + cooldown + memories
  teleport/PortalTeleportService.java crossing -> destination choice -> safe spot -> teleport
  effects/PortalEffects.java  particles + sounds (both ends)
  command/RdpCommand.java     /rdp status, /rdp reload
src/test/java/...             DoorGeometryTest, PortalConfigTest (pure Java)
```

## License

MIT - © 2026 WinniePatGG. See `LICENSE.txt`.
