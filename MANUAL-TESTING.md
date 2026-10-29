# Random Door Portals - manual test guide

Automated coverage lives in `src/test/java` (door geometry/keys, config loading/clamps).
Everything that needs a live server is below. Use a dedicated test world and
`debugLogging: true` for convenience. Restart the server between groups where noted.

Setup shorthand: "build a doorway" = 2×2 tall DoorBlock anywhere reachable, "walk through"
= walk into the door so your position center crosses the doorway cell, "crossing" = a
teleport happened with the portal particles/sound.

## 1. Fresh server start registers doors by chunk load
Steps: start server with the mod installed; `/rdp status` shows every dimension with
`0 door(s)`; walk around an area containing doors (recycle a village or your build).
Expected: once you have visited the chunks, `/rdp status` lists those doors; a door in an
unvisited chunk stays unknown until its chunk loads.

## 2. Basic teleport
Steps: build 3+ doorways spread out (2+ doors minimum). Open both door A and door B, walk
through A.
Expected: you are teleported to (a random) other door of the same dimension, portal
particles/sound at both ends; the arrival spot is next to the door, not inside a wall.

## 3. Only one registered door - no teleport
Steps: single doorway, open it, walk through.
Expected: no teleport (needs ≥ 2 doors); you pass the doorway normally.

## 4. Walking past, not through
Steps: stand next to a closed/open door and walk along the wall so your path merely touches
the door cell from outside (perpendicular to the doorway axis, e.g. walking along the wall
in front of the door).
Expected: no teleport. Only actual passage through the door cell counts.

## 5. Closed door does not teleport (requireEntranceOpen)
Steps: with `requireEntranceOpen: true`, walk toward a CLOSED door.
Expected: no teleport; the collision keeps you outside. Then open it and walk through:
teleport happens.

## 6. Closed destination doors skipped (requireDestinationOpen)
Steps: register doors A..C, close C (`requireDestinationOpen: true`), walk through A
repeatedly (cooldown 40t ≈ 2 s between tries).
Expected: C is never picked while closed; when you open C it starts being chosen.

## 7. Cooldown blocks instant ping-pong
Steps: `cooldownTicks: 200`, two doors. Walk through door A.
Expected: teleport to B; immediately walking back through B within 10 s does NOT teleport.
After 10 s walking through B works again (it is a fresh crossing).

## 8. Repeat-avoidance
Steps: three doors A/B/C, `avoidRepeatDestinations: true`. Teleport A→B; walk through B.
Expected: the next destination is not B's previous entrance (A) and not the door just left,
as long as other candidates exist.

## 9. Broken doors leave the registry
Steps: `/rdp status` shows N doors; break a doorway (both halves).
Expected: count drops by one right away; walking through any remaining door never picks the
broken one.

## 10. Restart persistence
Steps: note `/rdp status` counts, stop the server, start it again, `/rdp status`.
Expected: same counts without any chunk visit (loaded from
`data/<dimension>/randomdoorportals_doors.dat`). Crossing still works.

## 11. Stale entries from sneak-breaking while unloaded
Steps: break a door using an external tool/creative with the chunk unloaded is not
possible - instead: register a door, go > view distance away, break the door block from
there via an admin `setblock` with the chunk unloaded (or ask a friend), return to the chunk.
Expected: on chunk reload the scan notices the block is gone; the count drops; no
teleport uses it anymore.

## 12. Dimension gating
Steps: `allowNether: false`, `/rdp reload`. Go to the Nether and walk through a door there;
build doors in the Nether and walk through them.
Expected: no scanning in the Nether (`/rdp status`: 0 doors, `[disabled]`), no teleports;
Overworld keeps working.

## 13. Distance window
Steps: `minDistanceBlocks: 500, maxDistanceBlocks: 2000`, `/rdp reload`. Teleport through
doors repeatedly.
Expected: destinations converge into the 500..2000-block ring; `/rdp status` shows the
window. With `min > max` the min bound is dropped (documented fallback).

## 14. `/rdp reload`
Steps: edit `cooldownTicks` while the server runs; run `/rdp reload` as an operator;
`/rdp status`.
Expected: new value live. Running `/rdp reload` as a non-op fails with a permission error.
Note: `enabled: false` + reload deactivates teleports immediately.

## 15. Config errors do not kill the server
Steps: write garbage into `config/randomdoorportals.json`, restart server.
Expected: log warns, backup `randomdoorportals.broken.json` is created, defaults in effect
(`/rdp status` shows defaults).

## 16. Unloaded destination chunks are never force-loaded
Steps: register door D very far away (e.g. `/setblock` a door 20k blocks out and mark it
unloaded by leaving), walk through a door so a teleport would need D.
Expected: no server lag spike loading D's chunk (watch Spark/`stats` or the tick event
log); D is simply not chosen until its chunk is loaded; `debugLogging` shows it being
skipped.

## 17. Effects and debug toggles
Steps: set `particlesEnabled: false, soundsEnabled: false, debugLogging: true` and reload.
Expected: teleports still happen, silently; log lines appear for each crossing/scan.
Then set `chunkScanBudget: 1, scanTimeBudgetMillis: 1` and sprint into a new region.
Expected: scanning is slow but steady (performance budget honored), the game stays smooth.
