package justfatlard.follow_me;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Who is following whom, and the walking of it.
 *
 * <p>The follower is walked along the leader's own trail, not in a straight line at them: the
 * leader's footsteps are known to be a way through, round the corner and up the stairs, where a
 * beeline is a wall. Inside {@link FollowConfig#slack} blocks of the leader nothing is done at
 * all, so the follower can step aside, stop to look at something, turn round. Past it they are
 * tugged, gently at the edge and firmly further out, which is the rubber band. Their view is
 * never touched.
 *
 * <p>The tug is a velocity the server sends, not a teleport: the client keeps moving the player
 * itself, smoothly, and their own keys still bend it a little. Only a follower who has stopped
 * making progress is hopped onto the trail, and only a short way; one who falls further behind
 * than the leash has lost the leader, and it ends. So following is never a way to cross the world
 * at once.
 */
public final class Tethers {
	private Tethers() {}

	/** A place the leader stood or passed through. Standable where they were on their feet there. */
	record Crumb(Vec3 at, ResourceKey<Level> level, boolean standable) {}

	static final class Tether {
		final UUID follower;
		final UUID leader;
		final ArrayDeque<Crumb> trail = new ArrayDeque<>();
		Vec3 lastFollower;
		Vec3 lastLeader;
		int stuckTicks;
		int awayTicks;
		long lastPull = Long.MIN_VALUE / 2;
		long lastNag = Long.MIN_VALUE / 2;
		/** The leader's boat or cart the follower climbed into with them, to climb out of with them. */
		Entity boarded;
		/** One they climbed back out of, which is not offered again. */
		Entity declined;
		/** The last place the leader stood before going through to another level. */
		Crumb portal;

		Tether(UUID follower, UUID leader) {
			this.follower = follower;
			this.leader = leader;
		}
	}

	/** By follower, in the order they started: a leader's first follower walks closest. */
	private static final Map<UUID, Tether> tethers = new LinkedHashMap<>();

	private static final int TRAIL_LENGTH = 256;
	private static final double CRUMB_SPACING = 0.5;
	/** Close enough to a crumb to count it walked. */
	private static final double REACHED = 1.0;
	/** Each follower after the first keeps this much further back, so a line forms, not a pile. */
	private static final double SPACING = 1.5;
	private static final double WALK = 0.22;
	private static final double FASTEST = 0.8;
	private static final double GRAVITY = 0.08;
	private static final double DRAG = 0.98;
	private static final double JUMP = 0.42;
	private static final double SWIM_UP = 0.12;
	/** Two seconds of pulling without getting anywhere, and they are hopped onto the trail. */
	private static final int STUCK = 40;
	/** A minute on the far side of a portal the follower has not come through, and they are lost. */
	private static final int AWAY = 20 * 60;
	/** Falls just after a pull are the pull's doing, and do not hurt. */
	private static final int FALL_GRACE = 40;
	private static final int NAG = 60;

	/** Who this player is following, or null. */
	public static UUID leaderOf(UUID follower) {
		Tether tether = tethers.get(follower);
		return tether == null ? null : tether.leader;
	}

	/**
	 * Why this follower cannot start following that leader right now, or null if they can. Asked
	 * when the request is made and again on the yes, since either may have walked off between.
	 */
	public static String cannot(ServerPlayer follower, ServerPlayer leader) {
		if (follower == leader) return "You cannot follow yourself";
		if (follower.level() != leader.level() || follower.distanceTo(leader) > FollowConfig.leash()) {
			return "Too far apart - stand within " + FollowConfig.leash() + " blocks first";
		}
		// Round in a circle, everyone pulling everyone, nobody going anywhere.
		for (UUID up = leader.getUUID(); up != null; up = leaderOf(up)) {
			if (up.equals(follower.getUUID())) {
				return name(leader) + " is already following " + name(follower);
			}
		}
		return null;
	}

	public static void start(ServerPlayer follower, ServerPlayer leader) {
		String why = cannot(follower, leader);
		if (why != null) {
			Main.say(follower, why);
			Main.say(leader, why);
			return;
		}
		Tether previous = tethers.remove(follower.getUUID());
		if (previous != null) {
			ServerPlayer old = player(follower.level().getServer(), previous.leader);
			if (old != null && old != leader) Main.say(old, name(follower) + " stopped following you");
		}
		Tether tether = new Tether(follower.getUUID(), leader.getUUID());
		tether.lastFollower = follower.position();
		tether.lastLeader = leader.position();
		tethers.put(follower.getUUID(), tether);
		Main.say(follower, "Following " + name(leader) + " - /follow stop to stop");
		Main.say(leader, name(follower) + " is following you");
	}

	/** Stop following, and stop being followed: whichever this player is doing, both. */
	public static void stop(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		boolean any = false;
		for (Iterator<Tether> it = tethers.values().iterator(); it.hasNext(); ) {
			Tether tether = it.next();
			if (tether.follower.equals(player.getUUID())) {
				it.remove();
				any = true;
				Main.say(player, "Stopped following");
				ServerPlayer leader = player(server, tether.leader);
				if (leader != null) Main.say(leader, name(player) + " stopped following you");
			} else if (tether.leader.equals(player.getUUID())) {
				it.remove();
				any = true;
				Main.say(player, "Nobody is following you now");
				ServerPlayer follower = player(server, tether.follower);
				if (follower != null) Main.say(follower, name(player) + " let you go");
			}
		}
		if (!any) Main.say(player, "You are not following anybody");
	}

	/** A player who left: nobody follows them, and they follow nobody. */
	public static void forget(MinecraftServer server, UUID player) {
		for (Iterator<Tether> it = tethers.values().iterator(); it.hasNext(); ) {
			Tether tether = it.next();
			if (tether.leader.equals(player)) {
				it.remove();
				ServerPlayer follower = player(server, tether.follower);
				if (follower != null) Main.say(follower, "The one you were following left");
			} else if (tether.follower.equals(player)) {
				it.remove();
			}
		}
	}

	public static void tick(MinecraftServer server) {
		long now = server.overworld().getGameTime();
		Map<UUID, Integer> rank = new java.util.HashMap<>();
		for (Tether tether : List.copyOf(tethers.values())) {
			int place = rank.merge(tether.leader, 1, Integer::sum) - 1;
			ServerPlayer follower = player(server, tether.follower);
			ServerPlayer leader = player(server, tether.leader);
			if (follower == null || leader == null) {
				// The disconnect handler has it; a tick can land between the two.
				continue;
			}
			String ended = ended(follower, leader);
			if (ended != null) {
				end(tether, follower, leader, ended);
				continue;
			}
			walk(tether, follower, leader, place, now);
		}
	}

	/** Why this tether is over, or null while it holds. */
	private static String ended(ServerPlayer follower, ServerPlayer leader) {
		if (!follower.isAlive() || !leader.isAlive()) return "Stopped following: somebody died";
		if (follower.isSpectator() || leader.isSpectator()) return "Stopped following: spectating";
		if (follower.level() == leader.level() && follower.distanceTo(leader) > FollowConfig.leash()) {
			return "Lost " + name(leader) + " - too far behind";
		}
		return null;
	}

	private static void end(Tether tether, ServerPlayer follower, ServerPlayer leader, String why) {
		tethers.remove(tether.follower, tether);
		Main.say(follower, why);
		Main.say(leader, name(follower) + " is not following you any more");
	}

	private static void walk(Tether tether, ServerPlayer follower, ServerPlayer leader, int place, long now) {
		Vec3 leaderAt = leader.position();
		double leaderSpeed = horizontal(leaderAt.subtract(tether.lastLeader)).length();
		tether.lastLeader = leaderAt;
		drop(tether, leader);

		if (ride(tether, follower, leader)) {
			tether.lastFollower = follower.position();
			return;
		}

		boolean together = follower.level() == leader.level();
		if (together) {
			tether.awayTicks = 0;
			tether.portal = null;
		} else if (++tether.awayTicks > AWAY) {
			end(tether, follower, leader, "Lost " + name(leader) + " - they went through and you did not");
			return;
		}

		prune(tether, follower, together);
		double slack = FollowConfig.slack() + SPACING * place;
		double distance = together ? follower.distanceTo(leader) : Double.MAX_VALUE;

		Vec3 measured = follower.position().subtract(tether.lastFollower);
		tether.lastFollower = follower.position();

		// Their own mount goes where its rider steers it, and a push on the rider does nothing.
		if (distance <= slack || follower.isPassenger()) {
			tether.stuckTicks = 0;
			graceFall(tether, follower, now);
			return;
		}

		Vec3 target = target(tether, follower, leader, together);
		if (target == null) {
			// Stood on the last place the leader was before a portal: wait in it to be carried through.
			tether.stuckTicks = 0;
			graceFall(tether, follower, now);
			return;
		}

		// Gentle at the edge of the slack, firm by twice it: the band stretches before it snaps back.
		double over = together ? distance - slack : slack;
		double pull = Math.clamp(over / slack, 0.2, 1.0);
		double speed = Math.clamp(Math.max(WALK, leaderSpeed) + over * 0.04, WALK, FASTEST);

		Vec3 toward = horizontal(target.subtract(follower.position()));
		Vec3 heading = toward.lengthSqr() < 1.0E-4 ? Vec3.ZERO : toward.normalize().scale(speed);
		double x = measured.x + (heading.x - measured.x) * pull;
		double z = measured.z + (heading.z - measured.z) * pull;
		double y = vertical(follower, measured, target, toward.length());

		Vec3 motion = new Vec3(x, y, z);
		follower.setDeltaMovement(motion);
		follower.connection.send(new ClientboundSetEntityMotionPacket(follower.getId(), motion));
		follower.resetFallDistance();
		tether.lastPull = now;

		if (now - tether.lastNag >= NAG) {
			tether.lastNag = now;
			Main.say(follower, "Following " + name(leader));
		}

		if (horizontal(measured).length() < 0.03) tether.stuckTicks++;
		else tether.stuckTicks = Math.max(0, tether.stuckTicks - 1);
		if (tether.stuckTicks > STUCK && together) hop(tether, follower, leader);
	}

	/**
	 * Up and down, which the band leaves to the game except to jump and to swim up: left alone,
	 * gravity still has to happen. The client sets its speed from what is sent, so what is sent is
	 * the speed it would have had: last tick's fall, one more step of gravity on.
	 */
	private static double vertical(ServerPlayer follower, Vec3 measured, Vec3 target, double across) {
		double rise = target.y - follower.getY();
		if (follower.isInWater()) {
			return rise > 0.2 ? Math.max(measured.y, SWIM_UP) : measured.y;
		}
		if (follower.onGround()) {
			boolean step = rise > 0.5 && across < 3.0;
			return step || follower.horizontalCollision ? JUMP : 0;
		}
		return (measured.y - GRAVITY) * DRAG;
	}

	private static void graceFall(Tether tether, ServerPlayer follower, long now) {
		if (now - tether.lastPull < FALL_GRACE) follower.resetFallDistance();
	}

	/** A crumb wherever the leader has got to, and the oldest let go once there are plenty. */
	private static void drop(Tether tether, ServerPlayer leader) {
		Vec3 at = leader.position();
		ResourceKey<Level> level = leader.level().dimension();
		Crumb last = tether.trail.peekLast();
		if (last != null && last.level() != level) tether.portal = last;
		if (last != null && last.level() == level && last.at().distanceToSqr(at) < CRUMB_SPACING * CRUMB_SPACING) return;
		tether.trail.addLast(new Crumb(at, level, leader.onGround() && !leader.isPassenger()));
		while (tether.trail.size() > TRAIL_LENGTH) tether.trail.pollFirst();
	}

	/**
	 * Forget the trail behind wherever the follower is now. Nearest crumb, not first: a follower
	 * who cut a corner, or wandered ahead inside the slack, is not walked back to where they missed.
	 */
	private static void prune(Tether tether, ServerPlayer follower, boolean together) {
		ResourceKey<Level> here = follower.level().dimension();
		// Across now: what was left on the other side is behind them.
		if (together) {
			while (!tether.trail.isEmpty() && tether.trail.peekFirst().level() != here) tether.trail.pollFirst();
		}
		int nearest = -1;
		double best = Double.MAX_VALUE;
		int index = 0;
		for (Crumb crumb : tether.trail) {
			if (crumb.level() == here) {
				double d = crumb.at().distanceToSqr(follower.position());
				if (d < best) {
					best = d;
					nearest = index;
				}
			} else if (nearest >= 0) {
				break;
			}
			index++;
		}
		for (int i = 0; i < nearest; i++) tether.trail.pollFirst();
	}

	/**
	 * The next crumb along that they have not reached. Without the leader's level it is where the
	 * leader went through, kept apart from the trail so a leader running on far past it cannot push
	 * it out; null once standing in it, or with no way through known on this side.
	 */
	private static Vec3 target(Tether tether, ServerPlayer follower, ServerPlayer leader, boolean together) {
		if (!together) {
			Crumb portal = tether.portal;
			if (portal == null || portal.level() != follower.level().dimension()) return null;
			return portal.at().distanceTo(follower.position()) <= REACHED ? null : portal.at();
		}
		for (Crumb crumb : tether.trail) {
			if (crumb.level() != follower.level().dimension()) break;
			if (crumb.at().distanceTo(follower.position()) > REACHED) return crumb.at();
		}
		return leader.position();
	}

	/**
	 * Stuck behind something the trail goes round and they cannot: onto the latest place the
	 * leader stood on their feet, a step behind them. Only ever within the leash.
	 */
	private static void hop(Tether tether, ServerPlayer follower, ServerPlayer leader) {
		tether.stuckTicks = 0;
		Vec3 spot = null;
		for (Iterator<Crumb> it = tether.trail.descendingIterator(); it.hasNext(); ) {
			Crumb crumb = it.next();
			if (crumb.level() != leader.level().dimension()) break;
			if (crumb.standable() && crumb.at().distanceTo(leader.position()) >= SPACING) {
				spot = crumb.at();
				break;
			}
		}
		if (spot == null && leader.onGround()) spot = leader.position();
		if (spot == null) return;
		follower.stopRiding();
		follower.teleportTo(leader.level(), spot.x, spot.y, spot.z, Set.<Relative>of(),
			follower.getYRot(), follower.getXRot(), false);
		follower.resetFallDistance();
		tether.trail.clear();
		tether.lastFollower = follower.position();
		Main.say(follower, "Caught up with " + name(leader));
	}

	/**
	 * In the leader's boat or cart, if it has room: a boat seats two. Out again when they get out.
	 * Returns whether the follower is riding along, which needs no walking.
	 */
	private static boolean ride(Tether tether, ServerPlayer follower, ServerPlayer leader) {
		Entity vehicle = leader.getVehicle();
		if (tether.boarded != null && tether.boarded != vehicle) {
			if (follower.getVehicle() == tether.boarded) follower.stopRiding();
			tether.boarded = null;
		}
		if (vehicle != tether.declined) tether.declined = null;
		if (vehicle == null) return false;
		if (follower.getVehicle() == vehicle) {
			tether.boarded = vehicle;
			tether.trail.clear();
			return true;
		}
		// Climbed out of it themselves: that stands until the leader is in something else.
		if (tether.boarded == vehicle) {
			tether.boarded = null;
			tether.declined = vehicle;
		}
		if (vehicle == tether.declined) return false;
		if (follower.isPassenger() || follower.level() != vehicle.level() || follower.distanceTo(vehicle) > 6) return false;
		if (follower.startRiding(vehicle)) {
			tether.boarded = vehicle;
			tether.trail.clear();
			return true;
		}
		return false;
	}

	private static Vec3 horizontal(Vec3 v) {
		return new Vec3(v.x, 0, v.z);
	}

	static ServerPlayer player(MinecraftServer server, UUID id) {
		return server == null ? null : server.getPlayerList().getPlayer(id);
	}

	static String name(ServerPlayer player) {
		return player.getGameProfile().name();
	}
}
