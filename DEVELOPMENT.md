# Follow Me - Development Guide

For what the mod is and how it plays, see [README.md](README.md).

## Installation

Install server-side alongside its declared dependencies (see `fabric.mod.json`); connecting clients need only Pandorical. Version targets live in `gradle.properties` (Minecraft, loader, Fabric API) and `fabric.mod.json` (Java).

## Building

`./gradlew build` builds the jar into `build/libs`.

`python3 generate_icon.py` regenerates the mod menu icon from the vanilla jar.

## How the steering works

All of it is in `Tethers.java`. The server cannot move a player directly without fighting their client, so outside the slack it sends a velocity each tick (`ClientboundSetEntityMotionPacket`), blended from the follower's own last movement toward the next point on the leader's trail. The vertical part is the follower's own fall carried one gravity step on, not zero, or a pulled player would float.
