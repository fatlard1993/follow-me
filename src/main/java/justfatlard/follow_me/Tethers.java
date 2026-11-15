package justfatlard.follow_me;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Who is following whom, and the trail they follow by.
 *
 * <p>Every place the leader passes is kept as a crumb, and the follower is carried along those
 * crumbs on a theme ride (see {@link Rides}): round the corner and up the stairs the way the
 * leader went, not in a straight line at them. Once it has started, nothing but one of the two
 * stopping it, or leaving, parts them: not distance, not a portal, not a death.
 */
public final class Tethers {
	private Tethers() {}

	/**
	 * A place the leader passed. Standable where they were on their feet there; aloft where they
	 * were gliding, flying or climbing, which wants a creature that flies.
	 */
	record Crumb(Vec3 at, ResourceKey<Level> level, boolean standable, boolean aloft) {}

	static final class Tether {
		final UUID follower;
		/** Null for a rider wandering on their own, steering the ride themselves. */
		final UUID leader;
		final ArrayDeque<Crumb> trail = new ArrayDeque<>();
		Vec3 lastLeader;
		/** The last place the leader stood before going through to another level. */
		Crumb portal;
		/** The leader was dead last tick: wherever they turn up next is a fresh start, not a trail. */
		boolean leaderDown;
		Rides.Ride carriage;

		Tether(UUID follower, UUID leader) {
			this.follower = follower;
			this.leader = leader;
		}

		boolean wandering() {
			return leader == null;
		}
	}

	/** By follower, in the order they started: a leader's first follower rides closest. */
	private static final Map<UUID, Tether> tethers = new LinkedHashMap<>();

	private static final int TRAIL_LENGTH = 512;
	private static final double CRUMB_SPACING = 0.5;

	/** Who this player is following, or null. */
	public static UUID leaderOf(UUID follower) {
		Tether tether = tethers.get(follower);
		return tether == null ? null : tether.leader;
	}

	/** Following somebody: held in the seat, and kept from harm. */
	public static boolean carried(ServerPlayer player) {
		return tethers.containsKey(player.getUUID());
	}

	/**
	 * Why this follower cannot start following that leader right now, or null if they can. Asked
	 * when the request is made and again on the yes, since either may have walked off between.
	 * {@code near} is false for an op putting somebody behind a leader, who may be anywhere.
	 */
	public static String cannot(ServerPlayer follower, ServerPlayer leader, boolean near) {
		if (follower == leader) return "Nobody can follow themselves";
		if (follower.isSpectator() || leader.isSpectator()) return "Not while spectating";
		if (near && (follower.level() != leader.level() || follower.distanceTo(leader) > FollowConfig.leash())) {
			return "Too far apart - stand within " + FollowConfig.leash() + " blocks first";
		}
		// Round in a circle, everyone carrying everyone, nobody going anywhere.
		for (UUID up = leader.getUUID(); up != null; up = leaderOf(up)) {
			if (up.equals(follower.getUUID())) {
				return name(leader) + " is already following " + name(follower);
			}
		}
		return null;
	}

	public static String cannot(ServerPlayer follower, ServerPlayer leader) {
		return cannot(follower, leader, true);
	}

	/** Returns whether it started. */
	public static boolean start(ServerPlayer follower, ServerPlayer leader, boolean near) {
		String why = cannot(follower, leader, near);
		if (why != null) {
			Main.say(follower, why);
			Main.say(leader, why);
			return false;
		}
		Tether tether = new Tether(follower.getUUID(), leader.getUUID());
		tether.lastLeader = leader.position();
		replace(follower, tether);
		Main.say(follower, "Riding along behind " + name(leader) + " - /follow stop to get off");
		Main.say(leader, name(follower) + " is riding along behind you");
		return true;
	}

	/**
	 * Behind nobody: on a ride all the same, the creature still suiting the land and nothing able
	 * to hurt them, but going wherever they steer it. Returns whether it started.
	 */
	public static boolean wander(ServerPlayer rider) {
		if (rider.isSpectator()) {
			Main.say(rider, "Not while spectating");
			return false;
		}
		Tether previous = tethers.get(rider.getUUID());
		if (previous != null && previous.wandering()) {
			Main.say(rider, "Already wandering - /follow stop to get off");
			return false;
		}
		replace(rider, new Tether(rider.getUUID(), null));
		Main.say(rider, "Wandering - steer with your movement keys, jump to fly, sneak to land, /follow stop to get off");
		return true;
	}

	/** This tether in place of any the follower had, kept in the saddle: the creature under them carries on. */
	private static void replace(ServerPlayer follower, Tether tether) {
		Tether previous = tethers.remove(follower.getUUID());
		if (previous != null) {
			tether.carriage = previous.carriage;
			ServerPlayer old = previous.wandering() ? null : player(follower.level().getServer(), previous.leader);
			if (old != null && !old.getUUID().equals(tether.leader)) Main.say(old, name(follower) + " stopped following you");
		}
		tethers.put(follower.getUUID(), tether);
		// A bow drawn before mounting would otherwise still loose when let go.
		follower.stopUsingItem();
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
				Main.say(player, tether.wandering() ? "Off the ride" : "Stopped following");
				ServerPlayer leader = tether.wandering() ? null : player(server, tether.leader);
				Rides.release(tether, player, leader);
				if (leader != null) Main.say(leader, name(player) + " stopped following you");
			} else if (player.getUUID().equals(tether.leader)) {
				it.remove();
				any = true;
				Main.say(player, "Nobody is following you now");
				ServerPlayer follower = player(server, tether.follower);
				Rides.release(tether, follower, player);
				if (follower != null) Main.say(follower, name(player) + " let you go");
			}
		}
		if (!any) Main.say(player, "You are not following anybody");
	}

	/** A player who left: nobody follows them, and they follow nobody. */
	public static void forget(MinecraftServer server, UUID player) {
		for (Iterator<Tether> it = tethers.values().iterator(); it.hasNext(); ) {
			Tether tether = it.next();
			if (player.equals(tether.leader)) {
				it.remove();
				ServerPlayer follower = player(server, tether.follower);
				Rides.release(tether, follower, player(server, player));
				if (follower != null) Main.say(follower, "The one you were following left");
			} else if (tether.follower.equals(player)) {
				it.remove();
				Rides.release(tether, player(server, player), tether.wandering() ? null : player(server, tether.leader));
			}
		}
	}

	public static void tick(MinecraftServer server) {
		Map<UUID, Integer> rank = new HashMap<>();
		for (Tether tether : List.copyOf(tethers.values())) {
			if (tether.wandering()) {
				wanderTick(server, tether);
				continue;
			}
			int place = rank.merge(tether.leader, 1, Integer::sum) - 1;
			ServerPlayer follower = player(server, tether.follower);
			ServerPlayer leader = player(server, tether.leader);
			// The disconnect handler has it; a tick can land between the two.
			if (follower == null || leader == null) continue;
			if (follower.isSpectator() || !follower.isAlive()) {
				tethers.remove(tether.follower, tether);
				Rides.release(tether, follower, leader);
				Main.say(leader, name(follower) + " is not following you any more");
				continue;
			}
			Vec3 at = leader.position();
			// Capped: a teleport is a jump the ride skips, not a speed it should try to match.
			double leaderSpeed = Math.min(4, at.distanceTo(tether.lastLeader));
			tether.lastLeader = at;
			// A dead or spectating leader is waited for, not lost.
			boolean waiting = !leader.isAlive() || leader.isSpectator();
			if (waiting) tether.leaderDown = true;
			else drop(tether, leader);
			Rides.carry(tether, follower, leader, place, waiting ? 0 : leaderSpeed, waiting);
		}
	}

	private static void wanderTick(MinecraftServer server, Tether tether) {
		ServerPlayer rider = player(server, tether.follower);
		if (rider == null) return;
		if (rider.isSpectator() || !rider.isAlive()) {
			tethers.remove(tether.follower, tether);
			Rides.release(tether, rider, null);
			return;
		}
		Rides.wander(tether, rider);
	}

	/** A crumb wherever the leader has got to, and the oldest let go once there are plenty. */
	private static void drop(Tether tether, ServerPlayer leader) {
		Vec3 at = leader.position();
		ResourceKey<Level> level = leader.level().dimension();
		if (tether.leaderDown) {
			// Back from the dead somewhere else: the way they went before is no way to them now.
			tether.leaderDown = false;
			tether.trail.clear();
			tether.portal = null;
		}
		Crumb last = tether.trail.peekLast();
		if (last != null && last.level() != level) tether.portal = last;
		if (last != null && last.level() == level && last.at().distanceToSqr(at) < CRUMB_SPACING * CRUMB_SPACING) return;
		boolean aloft = leader.isFallFlying() || leader.getAbilities().flying || leader.onClimbable();
		tether.trail.addLast(new Crumb(at, level, leader.onGround() && !leader.isPassenger(), aloft));
		while (tether.trail.size() > TRAIL_LENGTH) tether.trail.pollFirst();
	}

	/** Where every other ride in this dimension stands right now. */
	static List<Vec3> ridesIn(ResourceKey<Level> level, Tether except) {
		List<Vec3> rides = new ArrayList<>();
		for (Tether tether : tethers.values()) {
			if (tether == except || tether.carriage == null) continue;
			Mob mount = tether.carriage.mount;
			if (mount != null && !mount.isRemoved() && mount.level().dimension() == level) rides.add(mount.position());
		}
		return rides;
	}

	static ServerPlayer player(MinecraftServer server, UUID id) {
		return server == null ? null : server.getPlayerList().getPlayer(id);
	}

	static String name(ServerPlayer player) {
		return player.getGameProfile().name();
	}
}
