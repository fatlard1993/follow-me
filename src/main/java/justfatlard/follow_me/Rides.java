package justfatlard.follow_me;

import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Theme ride: the follower is carried, not walked. They sit on a creature that suits the ground
 * underneath - a camel in the desert, a strider over lava, a nautilus at sea, a happy ghast over
 * a drop - which swaps for another as the land changes.
 *
 * <p>The creature is moved by the server along the leader's trail, exactly, with no physics: it
 * cannot fall, stick, drown or burn, and nothing hurts it or its rider. It has no saddle, so the
 * rider has no say in where it goes, and cannot climb off. They can still look around; the view
 * drifts back to the way ahead.
 */
public final class Rides {
	private Rides() {}

	/** What carries the rider, and how big. Scaled up where the creature is too small to sit on. */
	enum Theme {
		HORSE(EntityTypes.HORSE, 1.0),
		HAPPY_GHAST(EntityTypes.HAPPY_GHAST, 1.0),
		STRIDER(EntityTypes.STRIDER, 1.5),
		NAUTILUS(EntityTypes.NAUTILUS, 1.6),
		HOGLIN(EntityTypes.HOGLIN, 1.0),
		SKELETON_HORSE(EntityTypes.SKELETON_HORSE, 1.0),
		AXOLOTL(EntityTypes.AXOLOTL, 3.5),
		SNIFFER(EntityTypes.SNIFFER, 1.0),
		MOOSHROOM(EntityTypes.MOOSHROOM, 1.3),
		CAMEL(EntityTypes.CAMEL, 1.0),
		ARMADILLO(EntityTypes.ARMADILLO, 4.0),
		POLAR_BEAR(EntityTypes.POLAR_BEAR, 1.0),
		GOAT(EntityTypes.GOAT, 2.0),
		LLAMA(EntityTypes.LLAMA, 1.2),
		PANDA(EntityTypes.PANDA, 1.2),
		PIG(EntityTypes.PIG, 1.8),
		FOX(EntityTypes.FOX, 2.5),
		WOLF(EntityTypes.WOLF, 2.2),
		FROG(EntityTypes.FROG, 3.0),
		TURTLE(EntityTypes.TURTLE, 3.0);

		final EntityType<?> type;
		final double scale;

		Theme(EntityType<?> type, double scale) {
			this.type = type;
			this.scale = scale;
		}

		/** Ground under them changing - into water, off a cliff - swaps sooner than scenery does. */
		boolean substrate() {
			return this == HAPPY_GHAST || this == STRIDER || this == NAUTILUS;
		}
	}

	static final class Ride {
		Mob mount;
		Theme theme;
		Theme pending;
		int pendingTicks;
		double speed;
		Vec3 at;
		float yaw;
	}

	/** On every mount, so one left in the world by a crash is recognised and cleared on load. */
	private static final String TAG = "follow-me.ride";
	private static final Set<UUID> live = ConcurrentHashMap.newKeySet();

	private static final double FASTEST = 1.8;
	private static final double SPEED_UP = 0.06;
	private static final double SLOW_DOWN = 0.1;
	private static final double CATCH_UP = 0.15;
	/** Further behind than this along the trail and the ride skips ahead rather than racing. */
	private static final double SNAP = 96;
	private static final int SCENERY_TICKS = 20;
	private static final int SUBSTRATE_TICKS = 6;
	/** A share of the way back to straight ahead, each tick: slow enough to look around in. */
	private static final float VIEW_RETURN = 0.04F;
	private static final float VIEW_PITCH = 5F;

	public static boolean isMount(Entity entity) {
		return live.contains(entity.getUUID()) || entity.entityTags().contains(TAG);
	}

	/** One frame of being carried behind the leader. */
	static void carry(Tethers.Tether tether, ServerPlayer follower, ServerPlayer leader, int place) {
		Ride ride = tether.carriage;
		if (ride == null) {
			ride = tether.carriage = new Ride();
			ride.at = follower.position();
			ride.yaw = follower.getYRot();
		}

		if (follower.level() != leader.level()) {
			Tethers.Crumb portal = tether.portal;
			boolean atPortal = portal == null || portal.level() != follower.level().dimension()
				|| portal.at().distanceTo(ride.at) <= 1.5;
			if (atPortal) {
				cross(tether, ride, follower, leader);
				return;
			}
		}

		Vec3 aheadPoint = ahead(tether, ride, follower, leader);
		Theme wanted = theme(follower.level(), aheadPoint, standableAhead(tether, follower));
		if (ride.theme == null) ride.theme = wanted;
		if (wanted != ride.theme) {
			if (wanted != ride.pending) {
				ride.pending = wanted;
				ride.pendingTicks = 0;
			}
			if (++ride.pendingTicks >= (wanted.substrate() || ride.theme.substrate() ? SUBSTRATE_TICKS : SCENERY_TICKS)) {
				ride.theme = wanted;
				ride.pending = null;
				discard(ride, follower);
			}
		} else {
			ride.pending = null;
		}

		if (ride.mount == null || ride.mount.isRemoved() || follower.getVehicle() != ride.mount) {
			mount(ride, follower);
			if (ride.mount == null) return;
		}

		boolean together = follower.level() == leader.level();
		double gap = 2.5 + ride.mount.getBbWidth() + 1.5 * place;
		double behind = together ? behind(tether, ride, follower, leader) : Double.MAX_VALUE;
		if (together && behind > SNAP) {
			skipAhead(tether, ride, follower, leader, gap);
			mount(ride, follower);
			if (ride.mount == null) return;
			behind = behind(tether, ride, follower, leader);
		}

		double wantedSpeed = together ? Math.clamp((behind - gap) * CATCH_UP, 0, FASTEST) : FASTEST;
		ride.speed += Math.clamp(wantedSpeed - ride.speed, -SLOW_DOWN, SPEED_UP);
		double budget = together ? Math.min(ride.speed, Math.max(0, behind - gap)) : ride.speed;
		Vec3 before = ride.at;
		advance(tether, ride, follower, leader, budget, together);

		Vec3 moved = ride.at.subtract(before);
		if (moved.horizontalDistanceSqr() > 1.0E-4) {
			ride.yaw = (float) (Mth.atan2(-moved.x, moved.z) * Mth.RAD_TO_DEG);
		}

		Mob mount = ride.mount;
		// Up and down eased, so a leader hopping along does not shake the rider about.
		double smoothing = ride.theme == Theme.HAPPY_GHAST ? 0.15 : 0.35;
		double pathY = ride.theme.substrate() ? ride.at.y : floor(follower.level(), ride.at);
		double y = mount.getY() + (pathY - mount.getY()) * smoothing;
		mount.snapTo(ride.at.x, y, ride.at.z, ride.yaw, 0);
		mount.setYBodyRot(ride.yaw);
		mount.setYHeadRot(ride.yaw);
		mount.setDeltaMovement(Vec3.ZERO);
		mount.clearFire();
		mount.positionRider(follower);

		follower.setAirSupply(follower.getMaxAirSupply());
		follower.clearFire();
		follower.resetFallDistance();
		lookAhead(follower, ride.yaw);
	}

	/** The view eased back toward the way ahead: never seized, only drawn. */
	private static void lookAhead(ServerPlayer follower, float yaw) {
		float turn = Mth.wrapDegrees(yaw - follower.getYRot());
		float tilt = VIEW_PITCH - follower.getXRot();
		if (Math.abs(turn) < 0.5F && Math.abs(tilt) < 0.5F) return;
		follower.connection.send(new ClientboundPlayerRotationPacket(turn * VIEW_RETURN, true, tilt * VIEW_RETURN, true));
	}

	/** The next point along, which is what the ride should already look like it belongs to. */
	private static Vec3 ahead(Tethers.Tether tether, Ride ride, ServerPlayer follower, ServerPlayer leader) {
		if (follower.level() != leader.level() && tether.portal != null) return tether.portal.at();
		Tethers.Crumb next = tether.trail.peekFirst();
		if (next != null && next.level() == follower.level().dimension()) return next.at();
		return follower.level() == leader.level() ? leader.position() : ride.at;
	}

	private static boolean standableAhead(Tethers.Tether tether, ServerPlayer follower) {
		Tethers.Crumb next = tether.trail.peekFirst();
		return next == null || next.level() != follower.level().dimension() || next.standable();
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

	/** Spend this much distance walking the trail, letting go of each crumb as it is reached. */
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
					// Left over from the other side of a portal; behind them now.
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

	/** Far behind: on along the trail to a few lengths behind the leader, in one go. */
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
		if (landing == null) landing = leader.position();
		while (tether.trail.size() > keep) tether.trail.pollFirst();
		ride.at = landing;
		ride.speed = 0;
		discard(ride, follower);
		// The rider moved there first, so the new mount is spawned where their game already is.
		follower.teleportTo(follower.level(), landing.x, landing.y, landing.z, Set.<Relative>of(),
			follower.getYRot(), follower.getXRot(), false);
	}

	/**
	 * After the leader through a portal, carried across with them. The creature cannot go through
	 * with a rider, so it stays behind, and a fresh one suited to the other side meets them there.
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
		ride.theme = null;
		ride.speed = 0;
		tether.portal = null;
	}

	private static void mount(Ride ride, ServerPlayer follower) {
		discard(ride, follower);
		ServerLevel level = follower.level();
		if (ride.theme == null) ride.theme = theme(level, ride.at, true);
		Entity made = ride.theme.type.create(level, EntitySpawnReason.TRIGGERED);
		if (!(made instanceof Mob mob)) {
			if (made != null) made.discard();
			Main.LOGGER.warn("[{}] {} could not be made to ride", Main.MOD_ID, ride.theme);
			return;
		}
		mob.snapTo(ride.at.x, ride.at.y, ride.at.z, ride.yaw, 0);
		mob.setNoAi(true);
		mob.setNoGravity(true);
		mob.noPhysics = true;
		mob.setPermanentlyInvulnerable(true);
		mob.addTag(TAG);
		if (mob instanceof AgeableMob young) young.setAge(0);
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
		ride.mount = mob;
		level.sendParticles(ParticleTypes.POOF, ride.at.x, ride.at.y + 0.5, ride.at.z, 12, 0.6, 0.4, 0.6, 0.02);
		level.playSound(null, ride.at.x, ride.at.y, ride.at.z, SoundEvents.CHICKEN_EGG, SoundSource.PLAYERS, 0.7F, 1.4F);
	}

	private static void discard(Ride ride, ServerPlayer follower) {
		Mob mount = ride.mount;
		ride.mount = null;
		if (mount == null) return;
		live.remove(mount.getUUID());
		if (follower.getVehicle() == mount) follower.stopRiding();
		if (!mount.isRemoved()) {
			mount.ejectPassengers();
			mount.discard();
		}
	}

	/**
	 * The ride over, however it ended: off the creature and set down somewhere that holds. Where
	 * the ride was over lava, water or a drop, that is beside the leader instead, or the latest
	 * place they stood; where there is neither, a slow fall and a fireproof few seconds.
	 */
	static void release(Tethers.Tether tether, ServerPlayer follower, ServerPlayer leader) {
		Ride ride = tether.carriage;
		tether.carriage = null;
		if (ride == null || follower == null) return;
		discard(ride, follower);
		if (follower.isRemoved()) return;
		Vec3 spot = null;
		if (leader != null && leader.level() == follower.level()) {
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

	/** What the ground at this point calls for: what it is made of first, then where it is. */
	static Theme theme(ServerLevel level, Vec3 at, boolean standable) {
		BlockPos pos = BlockPos.containing(at);
		if (level.getFluidState(pos).is(FluidTags.LAVA) || level.getFluidState(pos.below()).is(FluidTags.LAVA)) {
			return Theme.STRIDER;
		}
		if (level.getFluidState(pos).is(FluidTags.WATER)
			|| (!standable && level.getFluidState(pos.below()).is(FluidTags.WATER))) {
			return Theme.NAUTILUS;
		}
		if (!standable && overDrop(level, pos)) return Theme.HAPPY_GHAST;

		Holder<Biome> biome = level.getBiome(pos);
		if (biome.is(BiomeTags.IS_NETHER)) {
			if (biome.is(Biomes.CRIMSON_FOREST)) return Theme.HOGLIN;
			if (biome.is(Biomes.SOUL_SAND_VALLEY)) return Theme.SKELETON_HORSE;
			return Theme.STRIDER;
		}
		if (biome.is(BiomeTags.IS_END)) return Theme.HAPPY_GHAST;

		if (biome.is(Biomes.LUSH_CAVES)) return Theme.AXOLOTL;
		boolean underground = pos.getY() < level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()) - 4;
		if (biome.is(Biomes.DRIPSTONE_CAVES) || biome.is(Biomes.DEEP_DARK) || underground) return Theme.SNIFFER;

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
		if (biome.is(BiomeTags.IS_SAVANNA) || biome.is(Biomes.WINDSWEPT_HILLS)
			|| biome.is(Biomes.WINDSWEPT_GRAVELLY_HILLS) || biome.is(Biomes.WINDSWEPT_FOREST)) return Theme.LLAMA;
		if (biome.is(BiomeTags.IS_MOUNTAIN)) return Theme.GOAT;
		return Theme.HORSE;
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
