# Follow Me - Development Guide

For what the mod is and how it plays, see [README.md](README.md).

## Installation

Install server-side alongside its declared dependencies (see `fabric.mod.json`); connecting clients need only Pandorical. Version targets live in `gradle.properties` (Minecraft, loader, Fabric API) and `fabric.mod.json` (Java).

## Building

`./gradlew build` builds the jar into `build/libs`.

`python3 generate_icon.py` regenerates the mod menu icon from the vanilla jar.

## How the steering works

All of it is in `Tethers.java`. The server cannot move a player directly without fighting their client, so outside the slack it sends a velocity each tick (`ClientboundSetEntityMotionPacket`), blended from the follower's own last movement toward the next point on the leader's trail. The vertical part is the follower's own fall carried one gravity step on, not zero, or a pulled player would float.

Theme rides are `Rides.java`: a mob with no AI, no gravity and no physics, spawned unsaddled so the rider never controls it, snapped along the trail each tick by the server. `mixin/PlayerMixin` keeps sneaking from dismounting; Fabric's `ALLOW_DAMAGE` keeps rider and mount unhurt; mounts carry the `follow-me.ride` entity tag so any left behind by a crash are discarded when their chunk loads.
