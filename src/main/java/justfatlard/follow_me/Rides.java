package justfatlard.follow_me;

import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import justfatlard.follow_me.integration.BigBoatsShips;
import justfatlard.pandorical.api.Capabilities;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.StructurePose;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Theme ride: the follower is carried, not walked. They sit on a creature that suits the ground
 * underneath - a camel in the desert, a strider over lava, a nautilus at sea - which swaps for
 * another as the land changes, and for one that flies when the leader glides, flies or climbs.
 *
 * <p>The creature is moved by the server along the leader's trail, exactly, with no physics: it
 * cannot fall, stick, drown or burn, and nothing hurts it or its rider. It has no saddle, so the
 * rider has no say in where it goes, and cannot climb off. They are on one every tick of it: a
 * swap puts them on the new creature before the old one goes.
 *
 * <p>They can still look around. Left alone a couple of seconds, the view drifts round to the
 * leader, slowly, and stops drifting the moment they move it themselves.
 */
public final class Rides {
	private Rides() {}

	/** How a creature keeps to the trail. */
	enum Kind {
		/** Walks on whatever is under the trail. */
		GROUND,
		/** Swims or wades the trail as it is: water, lava. */
		FLUID,
		/** Flies the trail as it is, up ladders and off cliffs. */
		FLYING
	}

	/** What carries the rider, and how big. Scaled up where the creature is too small to sit on. */
	enum Theme {
		HORSE(EntityTypes.HORSE, 1.0, Kind.GROUND),
		HOGLIN(EntityTypes.HOGLIN, 1.0, Kind.GROUND),
		SKELETON_HORSE(EntityTypes.SKELETON_HORSE, 1.0, Kind.GROUND),
		AXOLOTL(EntityTypes.AXOLOTL, 3.5, Kind.GROUND),
		SNIFFER(EntityTypes.SNIFFER, 1.0, Kind.GROUND),
		MOOSHROOM(EntityTypes.MOOSHROOM, 1.3, Kind.GROUND),
		CAMEL(EntityTypes.CAMEL, 1.0, Kind.GROUND),
		ARMADILLO(EntityTypes.ARMADILLO, 4.0, Kind.GROUND),
		POLAR_BEAR(EntityTypes.POLAR_BEAR, 1.0, Kind.GROUND),
		GOAT(EntityTypes.GOAT, 2.0, Kind.GROUND),
		LLAMA(EntityTypes.LLAMA, 1.2, Kind.GROUND),
		PANDA(EntityTypes.PANDA, 1.2, Kind.GROUND),
		PIG(EntityTypes.PIG, 1.8, Kind.GROUND),
		FOX(EntityTypes.FOX, 2.5, Kind.GROUND),
		WOLF(EntityTypes.WOLF, 2.2, Kind.GROUND),
		FROG(EntityTypes.FROG, 3.0, Kind.GROUND),
		TURTLE(EntityTypes.TURTLE, 3.0, Kind.GROUND),
		STRIDER(EntityTypes.STRIDER, 1.5, Kind.FLUID),
		NAUTILUS(EntityTypes.NAUTILUS, 1.6, Kind.FLUID),
		HAPPY_GHAST(EntityTypes.HAPPY_GHAST, 1.0, Kind.FLYING),
		BEE(EntityTypes.BEE, 4.0, Kind.FLYING),
		PARROT(EntityTypes.PARROT, 4.0, Kind.FLYING),
		ALLAY(EntityTypes.ALLAY, 3.5, Kind.FLYING),
		BAT(EntityTypes.BAT, 4.0, Kind.FLYING),
		/** A baby happy ghast, grown a little: what flies anywhere nothing else suits. */
		GHASTLING(EntityTypes.HAPPY_GHAST, 2.0, Kind.FLYING),
		/**
		 * Behind a leader at the wheel of a Big Boats ship: a {@link Sloop} of their own. The creature
		 * is only what the sloop is carried on, and nobody sees it.
		 */
		SLOOP(EntityTypes.ARMADILLO, 1.0, Kind.FLUID);

		final EntityType<?> type;
		final double scale;
		final Kind kind;

		Theme(EntityType<?> type, double scale, Kind kind) {
			this.type = type;
			this.scale = scale;
			this.kind = kind;
		}

		boolean baby() {
			return this == GHASTLING;
		}
	}

	static final class Ride {
		Mob mount;
		/** What the mount under them is; {@link #theme} is what it should be. */
		Theme ridden;
		Theme theme;
		Theme pending;
		int pendingTicks;
		double speed;
		Vec3 at;
		float yaw;
		/** Jumped rather than travelled this tick: no easing up or down, and the land suits at once. */
		boolean warped;
		float lastYaw = Float.NaN;
		float lastPitch;
		float nudgeYaw;
		float nudgePitch;
		int stillTicks;
		/** Steered by its rider rather than carried behind a leader: see {@link #wander}. */
		boolean steered;
		Vec3 velocity = Vec3.ZERO;
		/** Steered up off the ground, or carried off an edge: flying until it comes down to land. */
		boolean aloft;
		/** The latest place a steered ride stood on solid ground, for setting the rider down. */
		Vec3 lastStood;
		/** The leader's ship, while they are at her wheel; what the sloop is built to match. */
		BigBoatsShips.Ship ship;
		/** The top of the water under a sloop, as a block height. */
		int sea;
		/** How far above the creature the rider sits, read off them as they ride. */
		double seat = 0.3;
	}

	/** On every mount, so one left in the world by a crash is recognised and cleared on load. */
	private static final String TAG = "follow-me.ride";
	private static final Set<UUID> live = ConcurrentHashMap.newKeySet();

	/** Kept back along the trail: this, plus half again the creature's width, plus a spacing per place in line. */
	private static final double FOLLOW_GAP = 4.5;
	private static final double FOLLOW_SPACING = 3;
	/** The share of that gap kept clear in a straight line, wherever the trail goes. */
	private static final double PERSONAL_SPACE = 0.8;
	private static final double FASTEST = 1.8;
	private static final double SPEED_UP = 0.06;
	private static final double SLOW_DOWN = 0.1;
	private static final double CATCH_UP = 0.15;
	/** Further behind than this along the trail and the ride skips ahead rather than racing. */
	private static final double SNAP = 96;
	/** Two crumbs this far apart were a teleport, a pearl or a respawn: jumped, not flown through rock. */
	private static final double WARP = 12;
	/** A skip further than this takes the rider by teleport, so their game loads where they land. */
	private static final double FAR_SKIP = 32;
	private static final int SCENERY_TICKS = 20;
	private static final int SUBSTRATE_TICKS = 6;
	/** A steered ride's pace, in blocks a tick: a trot, a gallop when sprinting, and up or down. */
	private static final double WANDER_SPEED = 0.35;
	private static final double WANDER_SPRINT = 0.7;
	private static final double WANDER_CLIMB = 0.3;
	/** How much of the way to the wanted pace each tick: picks up gently, stops a little quicker. */
	private static final double WANDER_START = 0.15;
	private static final double WANDER_STOP = 0.25;
	/** What a walking ride steps up without a jump, and how far down it follows a slope before calling it an edge. */
	private static final double WANDER_STEP = 1.0;
	private static final int WANDER_REACH = 4;
	/** Coming down this close over the ground, a steered flier lands. */
	private static final double WANDER_LAND = 0.6;
	/** Two seconds of the rider not moving their view, and it starts to drift round to the leader. */
	private static final int LOOK_IDLE = 40;
	/** A share of the way round to the leader each tick, and at most this many degrees a tick. */
	private static final float VIEW_RETURN = 0.06F;
	private static final float VIEW_STEP = 2F;
	/** A turn of the view bigger than the drift could account for is the rider looking around. */
	private static final float LOOKING = 3F;

	public static boolean isMount(Entity entity) {
		return live.contains(entity.getUUID()) || entity.entityTags().contains(TAG);
	}

	/**
	 * One frame of being carried behind the leader. {@code waiting}: the leader is dead or
	 * spectating, and the ride holds still, rider in the saddle, until they are back.
	 */
	static void carry(Tethers.Tether tether, ServerPlayer follower, ServerPlayer leader, int place,
			double leaderSpeed, boolean waiting) {
		Ride ride = tether.carriage;
		if (ride == null) {
			ride = tether.carriage = new Ride();
			ride.at = follower.position();
			ride.yaw = follower.getYRot();
		}
		ride.warped = false;
		ride.steered = false;
		Vec3 before = ride.at;
		ride.ship = waiting ? null : BigBoatsShips.piloting(leader);

		if (!waiting) {
			if (follower.level() != leader.level()) {
				Tethers.Crumb portal = tether.portal;
				boolean atPortal = portal == null || portal.level() != follower.level().dimension()
					|| portal.at().distanceTo(ride.at) <= 1.5;
				if (atPortal) cross(tether, ride, follower, leader);
			}
			// Clear of her stern with the whole sloop: she reaches back from the wheel, and it forward from the seat.
			double clearance = ride.ship == null ? 0 : ride.ship.reach() + Sloop.BOW;
			travel(tether, ride, follower, leader, place, leaderSpeed, clearance);
		}

		Tethers.Crumb next = nextHere(tether, follower.level().dimension());
		Vec3 aheadPoint = next != null ? next.at() : ride.at;
		Theme wanted = theme(follower.level(), aheadPoint, next == null || next.standable(), next != null && next.aloft());
		if (ride.ship != null && PandoricalApi.hasCapability(follower, Capabilities.STRUCTURES)) {
			Integer sea = Sloop.sea(follower.level(), ride.at);
			if (sea != null) {
				wanted = Theme.SLOOP;
				ride.sea = sea;
			}
		}
		if (settle(ride, follower, wanted, before)) look(ride, follower, leader, waiting);
	}

	/**
	 * The ride brought round to the creature this place calls for, once it has called for it a
	 * little while, and set where {@link Ride#at} says, rider on, unharmed. Returns whether the
	 * rider is seated.
	 */
	private static boolean settle(Ride ride, ServerPlayer follower, Theme wanted, Vec3 before) {
		if (ride.theme == null || ride.warped) ride.theme = wanted;
		if (wanted != ride.theme) {
			if (wanted != ride.pending) {
				ride.pending = wanted;
				ride.pendingTicks = 0;
			}
			boolean quick = wanted.kind != Kind.GROUND || ride.theme.kind != Kind.GROUND;
			if (++ride.pendingTicks >= (quick ? SUBSTRATE_TICKS : SCENERY_TICKS)) {
				ride.theme = wanted;
				ride.pending = null;
			}
		} else {
			ride.pending = null;
		}

		Vec3 moved = ride.at.subtract(before);
		if (!ride.warped && moved.horizontalDistanceSqr() > 1.0E-4) {
			// Turned toward where it is going a little at a time: a wobbly trail is not a wobbly ride.
			float heading = (float) (Mth.atan2(-moved.x, moved.z) * Mth.RAD_TO_DEG);
			ride.yaw = Mth.rotLerp(0.25F, ride.yaw, heading);
		}

		ServerLevel level = follower.level();
		double pathY = ride.theme == Theme.SLOOP ? ride.sea + 1 - ride.seat
			: ride.theme.kind == Kind.GROUND ? floor(level, ride.at) : ride.at.y;
		boolean fresh = ride.mount == null || ride.mount.isRemoved() || ride.ridden != ride.theme
			|| ride.mount.level() != level;
		if (fresh) {
			mount(ride, follower, pathY);
			if (ride.mount == null) return false;
		} else if (follower.getVehicle() != ride.mount) {
			follower.startRiding(ride.mount, true, false);
		}

		Mob mount = ride.mount;
		// Up and down eased, so a leader hopping along does not shake the rider about.
		double smoothing = ride.theme.kind == Kind.FLYING ? 0.2 : 0.35;
		double y = fresh || ride.warped ? pathY : mount.getY() + (pathY - mount.getY()) * smoothing;
		mount.snapTo(ride.at.x, y, ride.at.z, ride.yaw, 0);
		mount.setYBodyRot(ride.yaw);
		mount.setYHeadRot(ride.yaw);
		mount.setDeltaMovement(Vec3.ZERO);
		mount.clearFire();
		if (mount instanceof AgeableMob young && ride.theme.baby()) young.setAge(AgeableMob.BABY_START_AGE);
		if (follower.getVehicle() == mount) {
			mount.positionRider(follower);
			ride.seat = follower.getY() - mount.getY();
		}
		if (ride.ridden == Theme.SLOOP) Sloop.move(mount, sloopPose(ride));

		follower.setAirSupply(follower.getMaxAirSupply());
		follower.clearFire();
		follower.resetFallDistance();
		return true;
	}

	/**
	 * Riding behind nobody: the rider steers with their movement keys, the way they look. On the
	 * ground the ride walks, stepping up a block and down any slope, never into a wall; jump takes
	 * it up into the air, and off an edge it holds its height rather than falling. Aloft or under
	 * water, jump climbs and sneak sinks, and it settles back on its feet when it comes down
	 * near the ground.
	 */
	static void wander(Tethers.Tether tether, ServerPlayer rider) {
		Ride ride = tether.carriage;
		if (ride == null) {
			ride = tether.carriage = new Ride();
			ride.at = rider.position();
			ride.yaw = rider.getYRot();
		}
		ServerLevel level = rider.level();
		if (!ride.steered) {
			ride.steered = true;
			ride.velocity = Vec3.ZERO;
			ride.aloft = ride.theme != null ? ride.theme.kind == Kind.FLYING : !rider.onGround() && !rider.isInLiquid();
		}
		ride.warped = false;
		Vec3 before = ride.at;
		steer(level, ride, rider.getLastClientInput(), rider.getYRot());
		boolean standing = !ride.aloft && !fluid(level, ride.at);
		if (standing) ride.lastStood = ride.at;
		settle(ride, rider, theme(level, ride.at, standing, ride.aloft), before);
	}

	private static void steer(ServerLevel level, Ride ride, Input input, float viewYaw) {
		double ahead = (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0);
		double aside = (input.left() ? 1 : 0) - (input.right() ? 1 : 0);
		Vec3 wish = Vec3.ZERO;
		if (ahead != 0 || aside != 0) {
			float sin = Mth.sin(viewYaw * Mth.DEG_TO_RAD);
			float cos = Mth.cos(viewYaw * Mth.DEG_TO_RAD);
			wish = new Vec3(-sin * ahead + cos * aside, 0, cos * ahead + sin * aside)
				.normalize().scale(input.sprint() ? WANDER_SPRINT : WANDER_SPEED);
		}
		ride.velocity = ride.velocity.lerp(wish, wish == Vec3.ZERO ? WANDER_STOP : WANDER_START);
		if (ride.velocity.lengthSqr() < 1.0E-5) ride.velocity = Vec3.ZERO;

		if (!ride.aloft && !fluid(level, ride.at)) {
			if (!input.jump()) {
				walk(level, ride);
				return;
			}
			ride.aloft = true;
		}
		fly(level, ride, input.jump() ? WANDER_CLIMB : input.shift() ? -WANDER_CLIMB : 0, input.jump());
	}

	/** Where a walker going on to this column comes to, or null for a wall in the way. */
	private record Footing(Vec3 at, boolean edge) {}

	private static void walk(ServerLevel level, Ride ride) {
		Vec3 v = ride.velocity;
		// Straight on, else along whichever side of the wall it met.
		for (Vec3 step : v == Vec3.ZERO ? new Vec3[] {Vec3.ZERO}
				: new Vec3[] {v, new Vec3(v.x, 0, 0), new Vec3(0, 0, v.z)}) {
			if (step != Vec3.ZERO && step.lengthSqr() < 1.0E-6) continue;
			Footing footing = footing(level, ride.at.x + step.x, ride.at.y, ride.at.z + step.z);
			if (footing == null) continue;
			ride.at = footing.at();
			// Over a drop it holds its height, and something that flies takes over.
			if (footing.edge()) ride.aloft = true;
			return;
		}
		ride.velocity = Vec3.ZERO;
	}

	private static Footing footing(ServerLevel level, double x, double y, double z) {
		BlockPos top = BlockPos.containing(x, y + WANDER_STEP, z);
		if (!level.isLoaded(top)) return null;
		for (int down = 0; down <= WANDER_REACH; down++) {
			BlockPos pos = top.below(down);
			if (!level.getFluidState(pos).isEmpty()) return new Footing(new Vec3(x, pos.getY() + 0.5, z), false);
			var shape = level.getBlockState(pos).getCollisionShape(level, pos);
			if (shape.isEmpty()) continue;
			double floor = pos.getY() + shape.max(Direction.Axis.Y);
			if (floor > y + WANDER_STEP + 0.05 || !open(level, new Vec3(x, floor, z))) return null;
			return new Footing(new Vec3(x, floor, z), false);
		}
		return new Footing(new Vec3(x, y, z), true);
	}

	private static void fly(ServerLevel level, Ride ride, double climb, boolean rising) {
		Vec3 v = ride.velocity;
		boolean moving = v.horizontalDistanceSqr() > 1.0E-6;
		Vec3[] tries = moving
			? new Vec3[] {new Vec3(v.x, climb, v.z), new Vec3(v.x, Math.max(climb, 0) + WANDER_STEP, v.z),
				new Vec3(v.x, climb, 0), new Vec3(0, climb, v.z), new Vec3(0, climb, 0)}
			: new Vec3[] {new Vec3(0, climb, 0)};
		boolean moved = false;
		for (Vec3 step : tries) {
			Vec3 to = ride.at.add(step);
			if (step.lengthSqr() < 1.0E-8 || !open(level, to)) continue;
			ride.at = to;
			moved = true;
			break;
		}
		if (!moved && moving) ride.velocity = Vec3.ZERO;
		boolean inFluid = fluid(level, ride.at);
		// Up out of the water is up in the air, not straight back in at the next step.
		if (!inFluid) ride.aloft = true;
		if (rising || inFluid) return;
		// Close enough over the ground with nobody holding it up: down on its feet.
		BlockPos below = BlockPos.containing(ride.at.x, ride.at.y - WANDER_LAND, ride.at.z);
		for (BlockPos pos : new BlockPos[] {BlockPos.containing(ride.at), below}) {
			var shape = level.getBlockState(pos).getCollisionShape(level, pos);
			if (shape.isEmpty()) continue;
			double floor = pos.getY() + shape.max(Direction.Axis.Y);
			if (floor <= ride.at.y + 0.01 && floor >= ride.at.y - WANDER_LAND) {
				ride.at = new Vec3(ride.at.x, floor, ride.at.z);
				ride.aloft = false;
				return;
			}
		}
	}

	/** Room for a rider here: nothing solid at the creature's feet or a block above, inside the world. */
	private static boolean open(ServerLevel level, Vec3 at) {
		BlockPos feet = BlockPos.containing(at);
		if (feet.getY() <= level.getMinY() || feet.getY() + 1 > level.getMaxY() || !level.isLoaded(feet)) return false;
		return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
			&& level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
	}

	private static boolean fluid(ServerLevel level, Vec3 at) {
		return !level.getFluidState(BlockPos.containing(at)).isEmpty();
	}

	/** Along the trail toward the leader, at a speed that eases up and down, or a jump where it must. */
	private static void travel(Tethers.Tether tether, Ride ride, ServerPlayer follower, ServerPlayer leader,
			int place, double leaderSpeed, double clearance) {
		boolean together = follower.level() == leader.level();
		double width = ride.mount != null ? ride.mount.getBbWidth() : 1.4;
		double gap = FOLLOW_GAP + 1.5 * width + FOLLOW_SPACING * place + clearance;
		if (together) {
			dropOtherSide(tether, follower.level().dimension());
			Tethers.Crumb next = tether.trail.peekFirst();
			Vec3 toward = next != null ? next.at() : leader.position();
			if (behind(tether, ride, follower, leader) > SNAP || ride.at.distanceTo(toward) > WARP) {
				skipAhead(tether, ride, follower, leader, gap);
			}
		}
		double behind = together ? behind(tether, ride, follower, leader) : Double.MAX_VALUE;
		// Never slower than the leader can go: an elytra is not left behind by a horse.
		double fastest = Math.max(FASTEST, leaderSpeed * 1.3 + 0.2);
		double wantedSpeed = together ? Math.clamp((behind - gap) * CATCH_UP, 0, fastest) : fastest;
		double up = Math.max(SPEED_UP, fastest * 0.05);
		double down = Math.max(SLOW_DOWN, ride.speed * 0.2);
		ride.speed += Math.clamp(wantedSpeed - ride.speed, -down, up);
		double budget = together ? Math.min(ride.speed, Math.max(0, behind - gap)) : ride.speed;
		if (together) {
			// The trail can bend back past the leader, as when they turn round: never ridden into
			// them, as the crow flies, however far behind the trail still says it is.
			double room = ride.at.distanceTo(leader.position()) - gap * PERSONAL_SPACE;
			budget = Math.min(budget, Math.max(0, room));
		}
		advance(tether, ride, follower, leader, budget, together);
	}

	/**
	 * The view drawn round to the leader while the rider leaves it be: never seized, only drawn,
	 * and let go the moment they look somewhere themselves. Turns are sent as small nudges on
	 * top of wherever the view is, so they never undo a move the rider has just made.
	 */
	private static void look(Ride ride, ServerPlayer follower, ServerPlayer leader, boolean waiting) {
		float yaw = follower.getYRot();
		float pitch = follower.getXRot();
		if (!Float.isNaN(ride.lastYaw)) {
			float turned = Mth.wrapDegrees(yaw - ride.lastYaw);
			float tilted = pitch - ride.lastPitch;
			boolean looking = Math.abs(turned - ride.nudgeYaw) > LOOKING || Math.abs(tilted - ride.nudgePitch) > LOOKING;
			ride.stillTicks = looking ? 0 : ride.stillTicks + 1;
		}
		ride.lastYaw = yaw;
		ride.lastPitch = pitch;
		ride.nudgeYaw = 0;
		ride.nudgePitch = 0;
		if (ride.stillTicks < LOOK_IDLE) return;

		float wantYaw = ride.yaw;
		float wantPitch = 5F;
		if (!waiting && leader.level() == follower.level()) {
			Vec3 to = leader.getEyePosition().subtract(follower.getEyePosition());
			double across = to.horizontalDistance();
			if (across > 1.5) {
				wantYaw = (float) (Mth.atan2(-to.x, to.z) * Mth.RAD_TO_DEG);
				wantPitch = Math.clamp((float) (-Mth.atan2(to.y, across) * Mth.RAD_TO_DEG), -50F, 50F);
			}
		}
		float turn = Mth.wrapDegrees(wantYaw - yaw);
		float tilt = wantPitch - pitch;
		float stepYaw = Math.abs(turn) < 1F ? 0 : Math.clamp(turn * VIEW_RETURN, -VIEW_STEP, VIEW_STEP);
		float stepPitch = Math.abs(tilt) < 1F ? 0 : Math.clamp(tilt * VIEW_RETURN, -VIEW_STEP, VIEW_STEP);
		if (stepYaw == 0 && stepPitch == 0) return;
		ride.nudgeYaw = stepYaw;
		ride.nudgePitch = stepPitch;
		follower.connection.send(new ClientboundPlayerRotationPacket(stepYaw, true, stepPitch, true));
	}

	/** The sloop round its rider, wherever the creature under them has got to. */
	private static StructurePose sloopPose(Ride ride) {
		Mob mount = ride.mount;
		return Sloop.pose(new Vec3(mount.getX(), mount.getY() + ride.seat, mount.getZ()), ride.yaw);
	}

	private static Tethers.Crumb nextHere(Tethers.Tether tether, ResourceKey<Level> here) {
		for (Tethers.Crumb crumb : tether.trail) {
			if (crumb.level() == here) return crumb;
		}
		return null;
	}

	/** Left over from the other side of a portal: behind them now. */
	private static void dropOtherSide(Tethers.Tether tether, ResourceKey<Level> here) {
		while (!tether.trail.isEmpty() && tether.trail.peekFirst().level() != here) tether.trail.pollFirst();
	}

	/** Along the trail, not as the crow flies: how far the ride still has to go to reach the leader. */
	private static double behind(Tethers.Tether tether, Ride ride, ServerPlayer follower, ServerPlayer leader) {
		ResourceKey<Level> here = follower.level().dimension();
		double total = 0;
		Vec3 from = ride.at;
		for (Tethers.Crumb crumb : tether.trail) {
			if (crumb.level() != here) continue;
			total += from.distanceTo(crumb.at());
			from = crumb.at();
		}
		return total + from.distanceTo(leader.position());
	}

	/** Spend this much distance going along the trail, letting go of each crumb as it is reached. */
	private static void advance(Tethers.Tether tether, Ride ride, ServerPlayer follower, ServerPlayer leader,
			double budget, boolean together) {
		ResourceKey<Level> here = follower.level().dimension();
		while (budget > 1.0E-6) {
			Tethers.Crumb crumb = tether.trail.peekFirst();
			Vec3 next;
			if (crumb != null && crumb.level() == here) {
				next = crumb.at();
			} else if (!together) {
				if (tether.portal == null) return;
				next = tether.portal.at();
			} else {
				if (crumb != null) {
					tether.trail.pollFirst();
					continue;
				}
				next = leader.position();
			}
			double distance = ride.at.distanceTo(next);
			if (distance <= budget) {
				ride.at = next;
				budget -= distance;
				if (crumb != null && crumb.level() == here) tether.trail.pollFirst();
				else return;
			} else {
				ride.at = ride.at.add(next.subtract(ride.at).scale(budget / distance));
				return;
			}
		}
	}

	/**
	 * Far behind, or the trail jumps: on to a few lengths behind the leader in one go. Near, the
	 * creature simply goes there with its rider on; far, the rider is teleported, so their game
	 * loads the place, and a fresh creature meets them.
	 */
	private static void skipAhead(Tethers.Tether tether, Ride ride, ServerPlayer follower, ServerPlayer leader, double gap) {
		ResourceKey<Level> here = follower.level().dimension();
		Vec3 landing = null;
		int keep = 0;
		int index = 0;
		for (Iterator<Tethers.Crumb> it = tether.trail.descendingIterator(); it.hasNext(); index++) {
			Tethers.Crumb crumb = it.next();
			if (crumb.level() != here) break;
			if (crumb.at().distanceTo(leader.position()) >= gap) {
				landing = crumb.at();
				keep = index;
				break;
			}
		}
		if (landing == null) {
			// No trail to land on, as when just started or just respawned: behind where they face.
			Vec3 facing = leader.getLookAngle().multiply(1, 0, 1);
			Vec3 back = facing.lengthSqr() < 1.0E-4 ? Vec3.ZERO : facing.normalize().scale(-gap);
			landing = leader.position().add(back);
		}
		while (tether.trail.size() > keep) tether.trail.pollFirst();
		boolean far = ride.at.distanceTo(landing) > FAR_SKIP;
		ride.at = landing;
		ride.speed = 0;
		ride.warped = true;
		if (far) {
			discard(ride, follower);
			follower.teleportTo(follower.level(), landing.x, landing.y, landing.z, Set.<Relative>of(),
				follower.getYRot(), follower.getXRot(), false);
		}
	}

	/**
	 * After the leader through a portal, carried across with them. The creature cannot go through
	 * with a rider, so it stays behind, and a fresh one suited to the other side meets them there
	 * the same tick.
	 */
	private static void cross(Tethers.Tether tether, Ride ride, ServerPlayer follower, ServerPlayer leader) {
		discard(ride, follower);
		ServerLevel there = leader.level();
		Vec3 landing = null;
		while (!tether.trail.isEmpty()) {
			Tethers.Crumb crumb = tether.trail.peekFirst();
			if (crumb.level() == there.dimension()) {
				landing = crumb.at();
				break;
			}
			tether.trail.pollFirst();
		}
		if (landing == null) landing = leader.position();
		follower.teleportTo(there, landing.x, landing.y, landing.z, Set.<Relative>of(),
			follower.getYRot(), follower.getXRot(), false);
		follower.resetFallDistance();
		ride.at = landing;
		ride.speed = 0;
		ride.warped = true;
		tether.portal = null;
	}

	/** The rider onto a fresh creature, then the old one away: never a tick on foot between. */
	private static void mount(Ride ride, ServerPlayer follower, double y) {
		ServerLevel level = follower.level();
		Entity made = ride.theme.type.create(level, EntitySpawnReason.TRIGGERED);
		if (!(made instanceof Mob mob)) {
			if (made != null) made.discard();
			Main.LOGGER.warn("[{}] {} could not be made to ride", Main.MOD_ID, ride.theme);
			return;
		}
		mob.snapTo(ride.at.x, y, ride.at.z, ride.yaw, 0);
		mob.setNoAi(true);
		mob.setNoGravity(true);
		mob.noPhysics = true;
		mob.setPermanentlyInvulnerable(true);
		mob.setPersistenceRequired();
		mob.addTag(TAG);
		if (mob instanceof AgeableMob young) young.setAge(ride.theme.baby() ? AgeableMob.BABY_START_AGE : 0);
		if (mob instanceof Bat bat) bat.setResting(false);
		if (ride.theme == Theme.SLOOP) mob.setInvisible(true);
		AttributeInstance scale = mob.getAttribute(Attributes.SCALE);
		if (scale != null) scale.setBaseValue(ride.theme.scale);
		// Known before it is added: adding it is what fires the load event that clears strays.
		live.add(mob.getUUID());
		if (!level.addFreshEntity(mob)) {
			live.remove(mob.getUUID());
			return;
		}
		// Forced: none of these is wearing a saddle, and none would take a rider without one.
		if (!follower.startRiding(mob, true, false)) {
			live.remove(mob.getUUID());
			mob.discard();
			return;
		}
		Mob old = ride.mount;
		Theme oldTheme = ride.ridden;
		ride.mount = mob;
		ride.ridden = ride.theme;
		if (ride.theme == Theme.SLOOP) {
			mob.positionRider(follower);
			ride.seat = follower.getY() - mob.getY();
			Sloop.spawn(mob, ride.ship, sloopPose(ride));
		}
		if (old != null) {
			if (oldTheme == Theme.SLOOP) Sloop.despawn(old);
			live.remove(old.getUUID());
			if (!old.isRemoved()) {
				old.ejectPassengers();
				old.discard();
			}
		}
		level.sendParticles(ParticleTypes.POOF, ride.at.x, y + 0.5, ride.at.z, 12, 0.6, 0.4, 0.6, 0.02);
		level.playSound(null, ride.at.x, y, ride.at.z, SoundEvents.CHICKEN_EGG, SoundSource.PLAYERS, 0.7F, 1.4F);
	}

	private static void discard(Ride ride, ServerPlayer follower) {
		Mob mount = ride.mount;
		Theme ridden = ride.ridden;
		ride.mount = null;
		ride.ridden = null;
		if (mount == null) return;
		if (ridden == Theme.SLOOP) Sloop.despawn(mount);
		live.remove(mount.getUUID());
		if (follower.getVehicle() == mount) follower.stopRiding();
		if (!mount.isRemoved()) {
			mount.ejectPassengers();
			mount.discard();
		}
	}

	/**
	 * The ride over: off the creature and set down somewhere that holds. Where the ride was over
	 * lava, water or a drop, that is beside the leader instead, or the latest place they stood;
	 * where there is neither, a slow fall and a fireproof few seconds.
	 */
	static void release(Tethers.Tether tether, ServerPlayer follower, ServerPlayer leader) {
		Ride ride = tether.carriage;
		tether.carriage = null;
		if (ride == null || follower == null) return;
		discard(ride, follower);
		if (follower.isRemoved()) return;
		Vec3 spot = null;
		if (ride.steered) {
			// Behind nobody: put down where it walked, or back where it last did if not far off.
			boolean standing = !ride.aloft && !fluid(follower.level(), ride.at);
			if (standing) spot = ride.at;
			else if (ride.lastStood != null && ride.lastStood.distanceTo(ride.at) <= FAR_SKIP * 2) spot = ride.lastStood;
		} else if (leader != null && leader.level() == follower.level() && leader.isAlive()) {
			if (leader.onGround() && !leader.isPassenger()) spot = leader.position();
			else {
				for (Iterator<Tethers.Crumb> it = tether.trail.descendingIterator(); it.hasNext(); ) {
					Tethers.Crumb crumb = it.next();
					if (crumb.level() == follower.level().dimension() && crumb.standable()) {
						spot = crumb.at();
						break;
					}
				}
			}
		}
		if (spot != null) {
			follower.teleportTo(follower.level(), spot.x, spot.y, spot.z, Set.<Relative>of(),
				follower.getYRot(), follower.getXRot(), false);
		} else {
			follower.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 15));
			follower.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 20 * 15));
		}
		follower.resetFallDistance();
	}

	/** Left behind by a crash or a logout mid-ride: nobody is on it, and it goes. */
	static void onLoad(Entity entity) {
		if (entity.entityTags().contains(TAG) && !live.contains(entity.getUUID())) {
			entity.ejectPassengers();
			entity.discard();
		}
	}

	/**
	 * What the trail at this point calls for: inside water or lava first, then in the air, then
	 * what the ground is made of, then where it is.
	 */
	static Theme theme(ServerLevel level, Vec3 at, boolean standable, boolean aloft) {
		BlockPos pos = BlockPos.containing(at);
		if (level.getFluidState(pos).is(FluidTags.LAVA)) return Theme.STRIDER;
		if (level.getFluidState(pos).is(FluidTags.WATER)) return Theme.NAUTILUS;
		if (aloft || (!standable && overDrop(level, pos))) return flying(level, pos);
		if (level.getFluidState(pos.below()).is(FluidTags.LAVA)) return Theme.STRIDER;
		if (!standable && level.getFluidState(pos.below()).is(FluidTags.WATER)) return Theme.NAUTILUS;

		Holder<Biome> biome = level.getBiome(pos);
		if (biome.is(BiomeTags.IS_NETHER)) {
			if (biome.is(Biomes.CRIMSON_FOREST)) return Theme.HOGLIN;
			if (biome.is(Biomes.SOUL_SAND_VALLEY)) return Theme.SKELETON_HORSE;
			return Theme.STRIDER;
		}
		if (biome.is(BiomeTags.IS_END)) return Theme.HAPPY_GHAST;

		if (biome.is(Biomes.LUSH_CAVES)) return Theme.AXOLOTL;
		if (biome.is(Biomes.DRIPSTONE_CAVES) || biome.is(Biomes.DEEP_DARK) || underground(level, pos)) return Theme.SNIFFER;

		if (biome.is(Biomes.MUSHROOM_FIELDS)) return Theme.MOOSHROOM;
		if (biome.is(Biomes.DESERT)) return Theme.CAMEL;
		if (biome.is(BiomeTags.IS_BADLANDS)) return Theme.ARMADILLO;
		if (biome.is(Biomes.CHERRY_GROVE)) return Theme.PIG;
		if (biome.value().coldEnoughToSnow(pos, level.getSeaLevel())) return Theme.POLAR_BEAR;
		if (biome.is(BiomeTags.IS_JUNGLE)) return Theme.PANDA;
		if (biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP)) return Theme.FROG;
		if (biome.is(Biomes.PALE_GARDEN)) return Theme.SKELETON_HORSE;
		if (biome.is(Biomes.DARK_FOREST)) return Theme.WOLF;
		if (biome.is(BiomeTags.IS_TAIGA)) return Theme.FOX;
		if (biome.is(BiomeTags.IS_BEACH)) return Theme.TURTLE;
		if (windswept(biome) || biome.is(BiomeTags.IS_SAVANNA)) return Theme.LLAMA;
		if (biome.is(BiomeTags.IS_MOUNTAIN)) return Theme.GOAT;
		return Theme.HORSE;
	}

	/** Something that flies, and suits where it flies. */
	private static Theme flying(ServerLevel level, BlockPos pos) {
		Holder<Biome> biome = level.getBiome(pos);
		if (biome.is(BiomeTags.IS_NETHER) || biome.is(BiomeTags.IS_END)) return Theme.HAPPY_GHAST;
		if (biome.is(Biomes.LUSH_CAVES) || biome.is(Biomes.DRIPSTONE_CAVES) || biome.is(Biomes.DEEP_DARK)
			|| underground(level, pos)) return Theme.BAT;
		if (biome.is(BiomeTags.IS_JUNGLE)) return Theme.PARROT;
		if (biome.is(Biomes.FLOWER_FOREST) || biome.is(Biomes.MEADOW) || biome.is(Biomes.SUNFLOWER_PLAINS)
			|| biome.is(Biomes.CHERRY_GROVE)) return Theme.BEE;
		if (biome.value().coldEnoughToSnow(pos, level.getSeaLevel()) || biome.is(BiomeTags.IS_MOUNTAIN)
			|| windswept(biome)) return Theme.ALLAY;
		return Theme.GHASTLING;
	}

	private static boolean windswept(Holder<Biome> biome) {
		return biome.is(Biomes.WINDSWEPT_HILLS) || biome.is(Biomes.WINDSWEPT_GRAVELLY_HILLS)
			|| biome.is(Biomes.WINDSWEPT_FOREST);
	}

	private static boolean underground(ServerLevel level, BlockPos pos) {
		return pos.getY() < level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()) - 4;
	}

	/**
	 * The top of whatever is under this point, a few blocks down at most. The trail is where the
	 * leader was, which is a saddle's height up when they rode; a camel walks on the sand.
	 */
	private static double floor(ServerLevel level, Vec3 at) {
		BlockPos start = BlockPos.containing(at.x, at.y + 0.5, at.z);
		for (int down = 0; down <= 4; down++) {
			BlockPos pos = start.below(down);
			var shape = level.getBlockState(pos).getCollisionShape(level, pos);
			if (!shape.isEmpty()) return pos.getY() + shape.max(Direction.Axis.Y);
		}
		return at.y;
	}

	/** Nothing to stand on for a few blocks down: a gap the leader flew or fell across. */
	private static boolean overDrop(ServerLevel level, BlockPos pos) {
		for (int down = 1; down <= 4; down++) {
			BlockPos below = pos.below(down);
			if (!level.getBlockState(below).getCollisionShape(level, below).isEmpty()) return false;
			if (!level.getFluidState(below).isEmpty()) return false;
		}
		return true;
	}
}
