# Follow Me - Development Guide

For what the mod is and how it plays, see [README.md](README.md).

## Installation

Install server-side alongside its declared dependencies (see `fabric.mod.json`); connecting clients need only Pandorical. Version targets live in `gradle.properties` (Minecraft, loader, Fabric API) and `fabric.mod.json` (Java).

## Building

`./gradlew build` builds the jar into `build/libs`.

`python3 generate_icon.py` regenerates the mod menu icon from the vanilla jar.

## How the riding works

`Tethers.java` keeps who follows whom and the leader's trail: a crumb every half block, marked standable when the leader was on their feet and aloft when they were gliding, flying or climbing.

`Rides.java` carries the follower along it: a mob with no AI, no gravity and no physics, spawned unsaddled so the rider never controls it, snapped along the trail each tick by the server. A swap spawns the new mob and seats the rider on it before the old one is discarded. Trail gaps over 12 blocks (teleports, respawns) are jumped rather than flown through; skips over 32 blocks teleport the rider so their client loads the landing. The view drift sends small relative `ClientboundPlayerRotationPacket` nudges, and stops whenever the rider's own rotation changes by more than the last nudge can explain.

`mixin/PlayerMixin` keeps sneaking from dismounting; Fabric's `ALLOW_DAMAGE` keeps rider and mount unhurt; mounts carry the `follow-me.ride` entity tag so any left behind by a crash are discarded when their chunk loads.
