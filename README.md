# Follow Me

Ask to follow another player, or ask them to follow you, and once they say yes the follower rides along behind the leader without touching a key, on a creature that suits the land underneath. For a long trip, a trip taken away from the keyboard, or a small child who would otherwise be left behind at the first hill.

## How it follows

The follower is sat on a creature that carries them along the leader's own trail, not a straight line at them, so they go round the corner and up the stairs the way the leader did. As the land changes, the creature swaps for another, with a poof, and the rider is moved straight across from one to the next.

- **Hands off.** The follower cannot steer, and sneaking does not climb off; `/follow stop` from either of you does.
- **Free to look.** The follower can look wherever they like. Left alone for a couple of seconds, their view drifts slowly round to the leader, and lets go the moment they move it again.
- **Never left behind.** Once it has started, nothing parts them but one of them stopping it, or leaving. Fall far behind and the ride skips ahead along the trail; through a portal it meets the follower on the other side with a creature suited to there; a leader who teleports is caught up with at once, and one who dies is waited for and caught up with wherever they come back.
- **Guaranteed safe.** The creature goes exactly where the leader went, without falling, sticking or being blocked by anything, and neither it nor its rider can be hurt, drown or burn. When it ends, the follower is set down beside the leader, or on the last place the leader stood, rather than over the lava or the drop the ride was crossing; with neither to hand, they get a slow fall and a fireproof few seconds instead.
- **Fast.** It keeps a few lengths behind the leader, easing up to speed to catch up and slowing to a stop when they stop, and keeps up with an elytra.
- **A line, not a pile.** Several people following one leader keep a little further back each, in the order they started.

A ride suits a small child well: once the offer is accepted, there is nothing to get wrong and nothing that can hurt them.

## Behind nobody

`/follow nobody` puts you on a ride with nobody to follow, and you steer it. It is the same ride in every other way: the creature suits the land and swaps as it changes, and neither you nor it can be hurt.

- **Movement keys** walk it the way you are looking; sprint gallops. It steps up a block, follows the ground down, and stops at a wall rather than going through it.
- **Jump** takes it up into the air on a flier; hold it to climb. **Sneak** sinks, and coming down near the ground it lands on its feet. Under water, jump and sneak swim up and down.
- **Off an edge** it holds its height, as a flier, rather than falling.
- `/follow stop` gets off, and sets you down where it stood, or where it last stood if that is close by.

Anybody can follow a player who is wandering, and a wanderer can stop and follow somebody without getting off.

## What carries you

By what is underfoot first, then where you are:

| Where | Ride |
|---|---|
| Lava, and most of the Nether | Strider |
| Water | Nautilus |
| Crimson forest | Hoglin |
| Soul sand valley, pale garden | Skeleton horse |
| Lush caves | Giant axolotl |
| Other caves and underground | Sniffer |
| Mushroom fields | Mooshroom |
| Desert | Camel |
| Badlands | Giant armadillo |
| Cherry grove | Big pink pig |
| Snow | Polar bear |
| Jungle | Panda |
| Swamp | Giant frog |
| Dark forest | Big wolf |
| Taiga | Giant fox |
| Beach | Giant turtle |
| Savanna, windswept hills | Llama |
| Mountains | Big goat |
| Anywhere else | Horse |

When the leader glides, flies or climbs, or crosses a drop, the ride takes to the air instead:

| Where | Flier |
|---|---|
| The Nether, the End | Happy ghast |
| Caves and underground | Giant bat |
| Jungle | Giant parrot |
| Flower forest, meadow, sunflower plains, cherry grove | Giant bee |
| Snow, mountains, windswept hills | Giant allay |
| Anywhere else | Baby happy ghast |

## Asking

Either way round, the one who did not ask is the one who answers: a follower agrees to be steered, a leader agrees to be followed.

- `/follow <player>` asks if you can ride along behind them.
- `/follow lead <player>` offers them a ride along behind you.
- `/follow nobody` puts you on a ride of your own to steer (see above).
- `/follow stop` gets you off, and stops anybody riding behind you. Either of you can end it at any time.
- `/follow accept <player>` and `/follow deny <player>` answer.
- `/follow assign <players> <leader>`, for ops, puts one or more players behind a leader without asking, wherever they are. A player already following somebody switches to the new leader without getting off. `nobody` as the leader puts them on rides of their own instead.

The ask arrives in the notice tray with buttons to answer it, or as a clickable line in chat for a game that cannot draw the tray. It lapses after a minute. Asking again, either way, takes back the first ask.

There is a **Follow** action menu too, with **Ride along** and **Give a ride** (picking who by face), **Wander**, and **Stop**.

## Requirements

- Minecraft, Fabric Loader, Fabric API: the versions declared in this mod's `gradle.properties`.
- [Pandorical](https://github.com/fatlard1993/pandorical) on the server. A client without it can still do all of it with `/follow`, and answers asks from chat.

## Pandorical

Pandorical is required on the server. The notice tray asks arrive in, the Follow action menu, the command help and the mod's page of the mod menu are all its. Following itself needs nothing on the client: the rides are ordinary creatures, so they work the same for a player without Pandorical.

## Configuration

`config/follow-me.properties`, or the mod's page in the mod menu for ops:

| Setting | Default | |
|---|---|---|
| `request_seconds` | `60` | How long an ask stands before it lapses. |
| `leash` | `32` | How close, in blocks, a follower must be to the leader for an ask to start it. `/follow assign` is not held to it. |
