# Follow Me

Ask to follow another player, or ask them to follow you, and once they say yes the follower is walked along behind the leader without touching a key. For a long trip, a trip taken away from the keyboard, or a small child who would otherwise be left behind at the first hill.

## How it follows

The follower walks the leader's own trail rather than a straight line at them, so they go round the corner and up the stairs the way the leader did instead of into the wall between.

- **Room to wander.** Within a few blocks of the leader (4 by default) nothing pulls at all: step aside, stop to look at something, turn round. Your view is never touched.
- **A rubber band, not a chain.** Past that you are tugged back into line: gently at first, firmly the further out you get. It is a push, not a teleport, so you still move smoothly and your own keys still bend your path a little.
- **Up and over.** A step or a block in the way is jumped, and water is swum up out of.
- **Boats.** When the leader gets into a boat with a free seat, you get in with them, and out again when they do. Climb out yourself and you are left on foot for that boat.
- **Portals.** When the leader goes through a portal, you are walked to where they went in and carried through by it, the same as they were.
- **Stuck.** Pulled for two seconds without getting anywhere, you are hopped a step behind the leader, onto a spot they stood on.
- **Falls** you are pulled off are not hurt.
- **A line, not a pile.** Several people following one leader keep a little further back each, in the order they started.

Following is never a way to cross the world at once. You have to be within the leash (32 blocks by default) of someone to start, the hop when stuck only ever lands a step behind them, and falling further behind than the leash loses them and ends it. So does a minute left on the far side of a portal they went through, either of you dying, or either of you leaving.

## Asking

Either way round, the one who did not ask is the one who answers: a follower agrees to be steered, a leader agrees to be followed.

- `/follow <player>` asks if you can follow them.
- `/follow lead <player>` asks them to follow you.
- `/follow stop` stops following, and stops anybody following you. Either of you can end it at any time.
- `/follow accept <player>` and `/follow deny <player>` answer.

The ask arrives in the notice tray with buttons to answer it, or as a clickable line in chat for a game that cannot draw the tray. It lapses after a minute. Asking again, either way, takes back the first ask.

There is a **Follow** action menu too, with **Follow**, **Lead** and **Stop**, picking who by face.

## Requirements

- Minecraft, Fabric Loader, Fabric API: the versions declared in this mod's `gradle.properties`.
- [Pandorical](https://github.com/fatlard1993/pandorical) on the server. A client without it can still do all of it with `/follow`, and answers asks from chat.

## Pandorical

Pandorical is required on the server. The notice tray asks arrive in, the Follow action menu, the command help and the mod's page of the mod menu are all its. Following itself needs nothing on the client: it is ordinary movement, so it works the same for a player without Pandorical.

## Configuration

`config/follow-me.properties`, or the mod's page in the mod menu for ops:

| Setting | Default | |
|---|---|---|
| `request_seconds` | `60` | How long an ask stands before it lapses. |
| `slack` | `4` | How far, in blocks, a follower may stray before being pulled back in line. |
| `leash` | `32` | How far behind, in blocks, a follower can fall before it ends. Also how close they must be to start. |
